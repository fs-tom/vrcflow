;;Collection of queries and visualization functions for
;;simulation history from the service-flow model.
(ns vrcflow.analysis
  (:require   [incanter [core :as i]
               [charts :as c]
               [stats :as s]]              
              [spork.sim  [core :as core]]
              [spork.util [temporal :as temp]]
              [vrcflow [services :as services]
                       [data :as data]
                       [stacked :as stacked]]))

;;Notes // Pending:
;;Priority rules may be considered (we don't here).  It's first-come-first-serve
;;priority right now.  Do they matter in practice?  Is there an actual
;;triage process?

;;History Queries
;;===============
(defn provider-trends [p]
  [(:name p)
   {:utilization (core/float-trunc (services/utilization p) 2)
    :clients     (count (:clients p))
    :capacity    (:capacity p)}])

;;Given a history, we can get data and trends from it...
(defn frame->location-quantities [[t ctx]]
  (->> (services/providers ctx)
       (mapv provider-trends)
       (into {})
       (assoc {:t t}  :trends)))

;;used for labeling location, highlights differences between
;;completed and exited.
(defn derive-location [c]
  (case (:location c)
    "exit" (if (empty? (:service-plan c)) "complete" "exit")
    (:location c)))

;;computes the client triends from a sim frame,
;;which gives us a time-standed index of client
;;locations.
(defn frame->clients [[t ctx]]
  (->> (services/clients ctx)
       (mapv #(hash-map :t t :location (derive-location %) :size (or (:size %) 1)))))

#_(def last-samples (atom nil))

;;want to include the ability to show clients....
(def ^:dynamic *use-size* nil)
(defn group-size [gr]
  (if-not *use-size*
    (count gr)
    (reduce + 0 (map :size gr))))

(defn location-samples [xs]
  (vec (for [sample xs
             [loc gr] (group-by :location sample)]
         {:t (:t (first gr)) :quantity (group-size gr)  #_(count gr)
          :location (or loc "empty")})))

(defn locations->map [xs]
  (reduce (fn [acc {:keys [t quantity location]}]
           (assoc acc location quantity))
          {} xs))

;;weak experiment in making better sampled flows.
;; (defn add-begin-points [t xs]
;;   (map (fn [r]
;;          (assoc r :t t :quantity 0)) xs))

;; (defn add-end-points [tprev t dropped? xs]
;;   (concat (map (fn [r]
;;                  (assoc r :t tprev)) xs)
;;           #_(map (fn [r]
;;                  (assoc r :t t :quantity (if (dropped? r) 0
;;                                              (:quantity r)))) xs)))

;; (defn accrue-locs [[accumulated final :as acc] [ls rs]]
;;   (let [tl (:t (first ls))
;;         tr (:t (first rs))
;;         lm     (locations->map ls)
;;         rm     (locations->map  rs)
;;         {:keys [dropped added] :as diffs}  (diff/diff-map lm rm)
;;         added (into #{} (map first added))
;;         trminus  (- tr 0.001)]
;;                                         ;diffs
;;     [(concat accumulated
;;              ls 
;;             ;;if we drop anything, we need to add an end-point.
;;             (when (seq added)
;;               (add-begin-points tl (filter #(added (:location %)) rs)) ;;zeroes at tl
;;               )
;;                                         ;(when (seq dropped)
;;             (add-end-points trminus tr #(dropped (:location %)) ls)
;;             )
;;      rs];;zeroes at tr - 0.001
;;     #_rs))

;; (defn location-diffs [xs]
;;   (->>  (partition 2 1 (partition-by :t xs))
;;         (reduce accrue-locs [nil nil])
;;         ((fn [[xs final]]
;;            (println (:t (first final)))
;;            (concat xs final)))))

(defn location-quantities->chart [xs]
  (-> (->>  (for [{:keys [t trends]} xs
                         [provider {:keys [utilization]}] trends]
                 {:t t :utilization utilization :provider provider})
            (i/dataset [:t :utilization :provider])
            (i/$rollup :sum  :utilization [:t :provider]))
      (stacked/->stacked-area-chart  :row-field :t :col-field :provider
                                     :values-field :utilization
                                     :x-label "time (minutes)"
                                     :y-label "Percent Utilization"
                                     :tickwidth 60
                                     :order-by (stacked/as-order-function  data/provider-order)
                                     :color-by data/provider-colors)))

(defn client-quantities->chart [xs]
  (-> (->>  xs
            (location-samples)
            (i/dataset [:t :quantity :location])
            (i/$rollup :sum  :quantity [:t :location]))      
      (stacked/->stacked-area-chart  :row-field :t :col-field :location
                                     :values-field :quantity
                                     :title (if *use-size*
                                              "Clients + Dependents Located by Time"
                                              "Clients Located by Time")
                                     :x-label "Time (minutes)"
                                     :y-label (if *use-size*
                                                "Quantity (clients + dependents)"
                                                "Quantity (clients)")
                                     :tickwidth 60
                                     :order-by (stacked/as-order-function  data/provider-order)
                                     :color-by data/provider-colors)))

;;show the distribution of provider utilization (across 1 or more replications)
(defn h->utilizations [h]
  (for [[gr xs] (group-by :provider
                          (for [pm (map :trends (concat (map frame->location-quantities h)))
                                [p m] (seq pm)]
                            (assoc (select-keys m [:utilization]) :provider p)))]
    [gr (map :utilization xs)]))

(defn hs->mean-utilizations [hs]
  (->> (for [h hs
             [gr xs] (group-by :provider
                               (for [pm (map :trends (concat (map frame->location-quantities h)))
                                     [p m] (seq pm)]
                                 (assoc (select-keys m [:utilization]) :provider p)))]
         [gr (s/mean (map :utilization xs))])
       (reduce (fn [acc [gr x]]
                 (assoc acc gr (conj (get acc gr []) x)))
               {})))

(defn utilization-plots
  [means]
  (let [the-plot
        (let [[gr xs] (first means)]
          (doto (c/box-plot  xs
                             :legend true
                             :x-label ""
                             :y-label "Percent Utilization"
                             :series-label gr
                             :title "Utilization by Provider"
                             :category-label "Utilization")))]
     (doseq [[gr xs] (rest means)]
       (c/add-box-plot the-plot xs :series-label gr :category-label "Utilization"))
     the-plot))
