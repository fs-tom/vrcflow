;;Collection of queries and visualization functions for
;;simulation history from the service-flow model.
(ns vrcflow.analysis
  (:require   [incanter [core :as i]
               [charts :as c]
               [stats :as s]]              
              [spork.sim  [core :as core]]
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
       (mapv #(hash-map :t t :location (derive-location %)))))

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
  (-> (->>  (for [sample xs
                  [loc gr] (group-by :location sample)]
              {:t (:t (first gr)) :quantity (count gr) :location (or loc "empty")})
            (i/dataset [:t :quantity :location])
            (i/$rollup :sum  :quantity [:t :location]))
      
      (stacked/->stacked-area-chart  :row-field :t :col-field :location
                                     :values-field :quantity
                                     :title "Clients Located by Time"
                                     :x-label "Time (minutes)"
                                     :y-label "Quantity (clients)"
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
               {} )))

(defn utilization-plots
  [means]
  (let [the-plot
        (let [[gr xs] (first means)]
          (doto (c/box-plot  xs
                             :legend true
                             :x-label ""
                             :y-label "Percent Utilization"
                             :series-label gr
                             :title "Utilization by Provider")))]
     (doseq [[gr xs] (rest means)]
       (c/add-box-plot the-plot xs :series-label gr))
     the-plot))
