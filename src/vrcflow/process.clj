;;ns for defining a process model based
;;on simple routing networks, transition
;;times, and capacities.
;;The idea is that we compile "down"
;;to a service network representation.
(ns vrcflow.process
  (:require [spork.util [table :as tbl]
                        [io :as io]
                        ;[general :as gen]
                        [sampling :as s]]
            [spork.sim [core :as core]]
            [spork.util.excel [core :as xl]]
            [spork.cljgraph [core :as g] [io :as gio]]
            [vrcflow [core :as vrc] [services :as services] [data :as data] [behavior :as beh]]
            ;;spec stuff
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
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

;;specifying processes for parsing.
;;work on this later...
(spec/def ::process-type #{:random-children})
(spec/def ::service-type #{:add-children})
(spec/def ::name (spec/or :keyword keyword?
                          :string  string?))
(spec/def ::posint pos-int?)
(spec/def ::posfloat #(and  (double? %) (pos? %)))
(spec/def ::num  (spec/or :zero zero? :int ::posint :float ::posfloat))
(spec/def ::n (spec/or :function fn?
                       :number ::num
                       ))

(spec/def ::weights (spec/map-of ::name ::n))
                                 
(defn random-child-count [children]
  (inc (rand-int
        (count children))))

;;Sample processes.
(def processes
 [{:type :random-children
   :name :default
   :n    1
   :service :add-children} ;;unspecified branches will follow default rules.
  ;;Needs Assessment could be here, but it's covered by default...
  {:name    "Begin Family Services"
   :type    :random-children   ;;draw random-nth between 0 and (count) children
   :service :add-children      ;;add drawn children to the service plan.
   :n       random-child-count ;;uniform distribution for number of children selected.
   }
  ;;or we could use distributions...
  {:name "Needs Assessment"
   :type :random-children
   :weights {"Comprehensive Processing" 1  ;;draw children, according to CDF
             "Standard Processing"      8 
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
(defn ->batch-sampler [n sampler]
  (->> sampler
       (s/->replications n)
       (s/->transform (fn [nd] (clear! sampler) nd))))

(defn select-by
  [body sampler n]
   (->> sampler
        (->batch-sampler n)
        (s/sample-from body)))

;;sampling rules must be keywords in the corpus.
;;we can probably pre-process the corpus...
;;rules get applied as functions to the context.
(defn child-selector [xs n & {:keys [replacement?]}]
  (let [sampler  (if replacement?
                   (s/->choice xs)
                   (s/->without-replacement xs))
        body (if (map? xs)
               (zipmap (keys xs) (keys xs))
               (zipmap xs xs))
        maxn  (count xs)
        entries (if (map? xs) (vals xs) xs)
        fst     (first entries)
        _       (assert (<= n maxn) "Number of children selected must be <= total children!")]
    (fn select
      ([] (select-by body sampler n))
      ([k]
       (cond (= maxn 1 k) fst
             (= maxn k)   entries ;;automatically selects all.
             (< k maxn)   (select-by body sampler k)
             :else (throw (Exception.
                           "Number of children selected must be <= total children!")))))))

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
                   0 (throw (Exception. (str [proc :requires :at-least 1 :child])))
                   (child-selector (or weights children)
                                   (if (number? n) n 1)))
        select-children  (cond (number? n)
                              (fn select-children [] (selector))
                          (fn? n)
                             (fn select-children [] (selector (n children)))
                          :else (throw (Exception. (str [:n-must-be-number-or-one-arg-fn]))))
        ]
    (merge proc
           {:end-service 
            (case service ;;only have one type of service at the moment.
              :add-children (fn [ctx ent] (add-children-as-services (select-children) ent))
              (throw (Exception. (str [:not-implemented service]))))
            :select-children select-children
            :selector selector
            :children children})))

;;once we have process services...
;;we can define our service network.

(defn process-map [routing-graph xs]
  (into {} (for [x xs]
             [(:name x) (if (= (:name x) :default) x
                            (process->service routing-graph x))])))


(defn process-based-service-network [routing-graph caps proc-map]
  (services/service-net
   caps
   (for [[from to w] (g/arc-seq routing-graph)]
     {:Name from :Services to :Minutes (max w 1)})
   nil))

(defn records->routing-graph [xs & {:keys [default-weight]
                                 :or {default-weight 1}}]
  (->>  (for [{:keys [From To Weight Enabled]} (tbl/as-records xs)
              :when Enabled]
          [From To (if (and Weight (pos? Weight))
                     Weight default-weight)]
          )
        (g/add-arcs g/empty-graph)))

;;we use LONG/MAX_VALUE as a substitute for infinity.  Ensure
;;that unconstrained nodes have max capacity.
(defn records->capacities [xs]
  (for [{:keys [Name Label Capacity] :as r} (tbl/as-records xs)]
    (if-not (pos? Capacity)
      (assoc r :Capacity Long/MAX_VALUE) ;;close to inf
      r)))

(comment ;testing
  
  (def psn (process-based-service-network
            (records->routing-graph data/proc-routing-table)
            (records->capacities    data/proc-cap-table)
            (tbl/as-records         data/proc-processes-table)))
  ;;we'd like to move from this eventually...
  (defn proc-seed-ctx
    [& {:keys [initial-arrivals]
        :or {initial-arrivals {:n 10 :t 1}} :as opts}]
    (let [{:keys [;default-behavior
                  default-interarrival
                  default-batch-size]} data/default-parameters]

      (->> (vrc/init (core/debug! vrc/emptysim) :initial-arrivals initial-arrivals
                     :default-behavior beh/client-beh
                     :service-network psn
                     :interarrival   default-interarrival
                     :batch-size     default-batch-size)
           (vrc/begin-t)
           (vrc/end-t))))
  
  (defn process-ctx [ctx]
    
    )
  
  )
;;so we have an entry for a default process in the process map.
;;Additionally, we have maps of processes, which have a :service
;;component and :on-service component.

;;Our goal then is to use the process-map to turn the routing map into
;;a service network, compatible with our service architecture.

;;In the original simulation, the behavior dictated most elements.
;;There's an entry point {:needs "Self Assessment"} (hardcoded at the
;;moment) and no :service-plan.  The entity is driven to find a
;;service plan and either get service, wait, or leave.

;;So during get-service-plan behavior, a service plan is computed
;;based on the needs of the entity (initially "Self Assessment"), and
;;the service network [:parameters :service-network] defined in the
;;entitystore.

;;We need to hook into this or override it.  What we'd like is for the
;;entity to consult a service network (that matches needs to
;;services).  This lines up a proposed set of services to acquire.

;;From there, the entity registers for service, and is either
;;allocated or not as service providers are made available.

;;So the entity's interest in services are decoupled from providing
;;services.

;;What we need to do is register these as providers.  A provider is
;;any entity with a provider-entity component.

;;So, processes can be providers...  we merely add an begin-service,
;;end-service component(s) to them.

;;The simplest process has an end-service, which adds some of its
;;children to the service plan.  We basically bypass the
;;"needs->service-plan" step and just add services directly.

;;The needs and services are identical, so we add [child child] pairs
;;to the service plan.

;;So, when applying services, the entire pipeline for handling
;;additional services is handled by the absence or presence of a
;;[begin/end-service] components, which provide hooks to modify the
;;service plan.

;;We need to coerce the process-graph into a service network.  To do
;;that, we need times for processes.  We have this information encoded
;;in the routing graph.

;;So...  We need Services [name label services minutes] Capacities
;;[name label focus capacity]

;;We can (and do) infer services from the routing graph.  Arcs
;;indicate services.  Weights indicate minutes (or time in service).

;;The implication here is that...  we can build a service network from
;;a routing graph.  The routing graph encodes provider->service
;;relations in its arcs.  The implication is that providers are also
;;hosts for self-identified services.  Arc weights indicate processing
;;time.

;;When we go to get service, if the service entity has
;;:begin/:end-service components, when that service is al/deallocated,
;;the entity will have said servicing fn applied.

;;We prime the processing narrative by creating a processing map,
;;which reads the process definitions, and creates process entities
;;that contain :end-service components.

;;We create a service network from the routing graph.

;;We then merge the entities defined by the process-map into the
;;simulation context / entity store.

;;Simulation now proceeds normally: Entities spawn, with an initial
;;need [for now, ENTRY].  They fill this need by consulting the
;;service network to create a service plan if one does not exist.  The
;;only service for the ENTRY need is a similar named ENTRY service,
;;which corresponds to the ENTRY node on the routing graph.

;;The entity requests service for ENTRY, causing it to be advertised
;;for service providers.

;;Per the service network (defined by the nodes in the routing graph),
;;ENTRY is the only node that provides the ENTRY service.

;;Entity is assigned to the ENTRY provider, active service is ENTRY,
;;and if [ENTRY begin-service] component exists, this function is
;;applied to the entity and context updated.  (Perhaps we lift this to
;;the messaging protocol, and allow the entity to determine how to
;;proceed).

;;ENTRY service takes 0 time.  Allocating the ENTRY provider to the
;;ENTRY service.

;;We assume (see digression) the every service has positive wait-time,
;;thus we ensure that zero-wait edges are replaced by 1.

;;So, entity is allocated to ENTRY for service, with a wait-time of 1.
;;Time passes.  Next time step, entity is updated.  During
;;update-entities system, entity is updated and goes through
;;finish-service behavior.  finish-service invokes
;;services/deallocate-provider.  This invokes the :end-service
;;function component of the provider, altering entity's service plan
;;accordingly.  So, entity's service-plan was {ENTRY ENTRY} with
;;:active-service ENTRY.  During this step, :active-service is nil,
;;service-plan is now {"JRPC Holding Area" "JRPC Holding Area"}



;;Begin Digression
;;[NOTE: we don't have 0 wait times in the original
;;VRCflow setup.  We need to account for this somewhere...]  One way
;;to look at 0-cost transitions is that a) capacity is meaningless
;;since we're not waiting there.  b) they're just transitive
;;references to services lower in the chain.  c) they're really
;;services that take a minimum amount of time (say 1 minute).

;;The easy cop-out here is to say the service is 1 minute long, or ms
;;or whatever.  That ensures atomic progress.

;;Or, we flatten-out all transitive services and map the transitive
;;reference (the need) to the first relevant service, i.e.  a service
;;with non-zero wait-time.

;;Another interpretation is that the wait-time is not a constraint,
;;but capacity is.  In this fashion, infinitessimal wait-times should
;;allow entities to flow through on - to the outside obersever - the
;;same whole-unit time step, although they're actually happening at
;;infinitessimal intervals.

;;This would allow us to record the flow of services/processes, i.e.
;;all of the transitions visited.

;;we'll cop-out for now, and assume minutes.
;;If wait-time is 0, wait-time is 1 minute.
;;End Digression
