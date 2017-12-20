;;ns for defining a process model based
;;on simple routing networks, transition
;;times, and capacities.
;;The idea is that we compile "down"
;;to a service network representation.
(ns vrcflow.process
  (:require [spork.util [table :as tbl]
                        [io :as io]
                        [general :as gen]
                        [sampling :as s]]
            [spork.util.excel [core :as xl]]
            [spork.cljgraph [core :as g] [io :as gio]]
             ))

;;testing...
(def p  (io/alien->native (io/hpath "Documents/repat/repatdata.xlsx")))
(def wb
  (->> (xl/xlsx->tables p)
       (reduce-kv (fn [acc k v] (assoc acc (keyword k) (tbl/keywordize-field-names v)))
                  {})))

(defn route-graph [xs]
  (->>  xs
        (map (juxt :From :To (comp long :Weight)))
        (g/add-arcs g/empty-graph)))

(defn wb->routes [wb]
  (route-graph (tbl/table-records (get wb :Routing))))

;;We've got routes defined via a DAG

;;How do we define a service network?

;;How do we express cycles?
(def rg (wb->routes wb))

;;I think what we'll do is..
;;Entities that follow a routing graph
;;will infer a service from the graph.

;;If the routing graph provides more than one child,
;;we MUST have a rule to determine which child(ren)
;;to add to the service plan.

;;Ex
(def processes
 [{:type :random-children
   :name :default
   :n    1
   :service :add-children} ;;unspecified branches will follow default rules.
  ;;Needs Assessment could be here, but it's covered by default...
  {:name    "Begin Family Services"
   :type    :random-children ;;draw random-nth between 0 and (count) children
   :service :add-children    ;;add drawn children to the service plan.
   :n       (fn [children]
              (inc (rand-int
                    (count children)))) ;;uniform distribution for number of children selected.
   }
  ;;or we could use distributions...
  {:name "Needs Assessment"
   :type :random-children
   :weights {"Comprehensive Processing" 1  ;;draw children, according to CDF
             "Standard Processing"      10 
             "Fast Track Processing"    1}
   :service :add-children
   :n 1}])

;;basic oqperations on processing nodes.
;;So, the goal is to go from process graph, to processes, to services.
;;This then fits into our service model from VRCflow.  From there
;;we should be able to execute the sim like normal.
;;What about arrivals? Later...
(defprotocol IService
  (service [s client ctx]))

(defn map-service [m client ctx]
  (if-let [s (:service m)]
    (s client ctx)
    ctx))

(extend-protocol IService
  clojure.lang.PersistentArrayMap
  (service [s client ctx]
    (map-service s client ctx))
  clojure.lang.PersistentHashMap
  (service [s client ctx]
    (map-service s client ctx)))

;;This basically queues up identical
;;[service need] pairs derived from children.
;;Assumably, only one child exists that can suit
;;said need (i.e., the need is for that exact child service
;;provider, where the provider supplies a service of the same
;;type as its label on the graph).
(defn add-children-as-services [children ent]
  (let [c (get ent :service-plan [])]
    (assoc ent :service-plan
           (->> (for [chld children]
                  [chld chld])
                (into (get ent :service-plan {})
                      )))))

;;reset the sampler if we're sampling without replacements.
(defn clear! [nd]
  (when-let [f (some-> nd
                       (meta)
                       (:clear))]
    (do (f) nd)))

;;This is a combination 
(defn ->batch-sampler [n nd]
  (->> nd
       (s/->replications n)
       (s/->transform (fn [nd] (clear! nd) nd))))

(defn select-by
  [body sampler n]
   (->> sampler
        (->batch-sampler n)
        (s/sample-from body)))

;;sampling rules must be keywords in the corpus.
;;we can probably pre-process the corpus...
;;rules get applied as functions to the context.
(defn child-selector [xs n & {:keys [replacement?]}]
  (cond (map? xs) (let [sampler  (if replacement?
                                   (s/->choice xs)
                                   (s/->without-replacement xs))
                        body (zipmap (keys xs) (keys xs))]
                    (fn select
                      ([]  (select-by body sampler n))
                      ([k] (select-by body sampler k))))
        (seq xs)
        (let [childset   (set xs)
              maxn       (count childset)]
          (assert (<= n maxn) "Number of children selected must be <= total children!") 
          (cond (= maxn 1) (let [fst (first xs)]
                             (fn [] fst))
                (= maxn n) (fn [] xs) ;;automatically selects all.
                :else (let [sampler  (if replacement?
                                       (s/->choice xs)
                                       (s/->without-replacement xs))
                            body (zipmap xs xs)]
                        (fn select
                          ([]  (select-by body sampler n))
                          ([k] (select-by body sampler k))))))
          :else (throw (Exception.
                        (str [:unable-to-create-child-sampler!])))))

;;now we can define child selectors that sample without replacement
;;or with replacement.  Our typical use case is without replacement
;;though.

;;Process nodes basically define a self-named service that
;;  a) services entities by (typically) updating the service plan
;;     to include the "next" service (or services)
;;  b) the update service plan should reflect one or more
;;     children, based on the following semantics:
;;     1: If there's only one child, the next service is the child (easy).
;;     2+:  Depending on the type of the node (specified where?) 
;;

(defmulti process->service (fn [process-graph process] (:type process)))
(defmethod process->service :random-children [pg {:keys [name type n service weights] :as proc
                                                  :or {n 1}}]
  (let [children  (g/sinks pg name)
        selector (case (count children)
                   0 (throw (Exception. (str [:requires :at-least 1 :child])))
                   (child-selector (or weights children)
                                   (if (number? n) n 1)))
        select-children  (cond (number? n)
                              (fn select-children [] (selector))
                          (fn? n)
                             (fn select-children [] (selector (n children)))
                          :else (throw (Exception. (str [:n-must-be-number-or-one-arg-fn]))))
        ]
    (merge proc
           {:on-service 
            (case service ;;only have one type of service at the moment.
              :add-children (fn [ent] (add-children-as-services (select-children) ent))
              (throw (Exception. (str [:not-implemented service]))))
            :select-children select-children
            :selector selector
            :children children})))


    
