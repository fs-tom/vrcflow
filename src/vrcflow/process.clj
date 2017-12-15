;;ns for defining a process model based
;;on simple routing networks, transition
;;times, and capacities.
;;The idea is that we compile "down"
;;to a service network representation.
(ns vrcflow.process
  (:require [spork.util [table :as tbl]
                        [io :as io]]
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
         

