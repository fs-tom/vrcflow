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
  {:name    "Begin Family Service"
   :type    :random-children ;;draw random-nth between 0 and (count) children
   :service :add-children    ;;add drawn children to the service plan.
   }
  ;;or we could use distributions...
  {:name "Needs Assessment"
   :type :random-children
   :weights {"Comprehensive Processing" 1  ;;draw children, according to CDF
             "Standard Processing"      10 
             "Fast Track Processing"    1}
   :service :add-children
   :n 1}])

;;basic operations on processing nodes.
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
;;sampling rules must be keywords in the corpus.
;;we can probably pre-process the corpus...
(defn child-selector [xs n & {:keys [replacement?]}]
  (cond (map? xs) (let [sampler  (->> (if replacement?
                                        (s/->choice xs)
                                        (s/->without-replacement xs))
                                      (s/->replications n))
                        body (zipmap (keys xs) (keys xs))]
                    (fn []
                      (s/sample-from body sampler)))                     
        (seq xs)
        (let [childset   (set xs)
              maxn       (count childset)]
          (assert (<= n maxn) "Number of children selected must be <= total children!") 
          (cond (= maxn 1) (let [fst (first xs)]
                             (fn [] fst))
                (= maxn n) (fn [] xs) ;;automatically selects all.
                :else (let [sampler  (->> (if replacement?
                                            (s/->choice xs)
                                            (s/->without-replacement xs))
                                          (s/->replications n))
                            body (zipmap xs xs)]
                        (fn []
                          (s/sample-from body sampler)))))
          :else (throw (Exception.
                        (str [:unable-to-create-child-sampler!])))))
                
                 
;;Process nodes basically define a self-named service that
;;  a) services entities by (typically) updating the service plan
;;  b) the update to an entity is a function of 
(defmulti process->service (fn [process-graph process]) (:type process))
(defmethod process->service :random-children [pg {:keys [type n service weights]}]
  (let [children  (g/get-sinks g)
        select-children (case (count chi
                          ]
    (case service
      :add-children (add-children-as-services children ent)
  )
  
  
    
