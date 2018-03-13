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
            [spork.entitysystem.store :as store]
            [spork.util.excel [core :as xl]]
            [spork.cljgraph [core :as g] [io :as gio]]
            [vrcflow [core :as vrc] [services :as services] [data :as data] [behavior :as beh]]
            ;;spec stuff
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
             ))

;;aux functions
(defn keywordize-tables [m]
  (reduce-kv (fn [acc k v] (assoc acc (keyword k) (tbl/keywordize-field-names v)))
             {} m))

(defn route-graph [xs]
  (->>  xs
        (map (juxt :From :To (comp long :Weight)))
        (g/add-arcs g/empty-graph)))

;;We've got routes defined via a DAG

;;How do we define a service network?

;;How do we express cycles?
;(def rg (wb->routes wb))



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

;;alter the spec for weights definition
(spec/def ::weights (spec/map-of ::name ::n))

(defn random-child-count [children]
  (inc (rand-int (count children))))

;;Sample processes.
#_(def processes
 [{:type :random-children
   :name :default-process
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
(comment (defprotocol IService
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
)

(defn push-service-group [ent xs ending-service]
  (-> ent
      (services/push-plan {ending-service ending-service})
      (services/push-plan  xs)))

;;This basically queues up identical
;;[service need] pairs derived from children.
;;Assumably, only one child exists that can suit
;;said need (i.e., the need is for that exact child service
;;provider, where the provider supplies a service of the same
;;type as its label on the graph).
(defn add-children-as-services
  ([children ent]
   (let [c (get ent :service-plan [])]
     (assoc ent :service-plan
            (->> (for [chld children]
                   [chld chld])
                 (into (get ent :service-plan {})
                       )))))
  ([children ent tgt]
   (if-not tgt 
     (add-children-as-services children ent)
     (push-service-group ent (zipmap children children) tgt))))

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

(defn get-pre-processed
  "Find any pre-processed service selections, indicatingx that the client
   has over-ridden the default processing pipeline for a service."
  [ent process-name]
  (-> ent :pre-processed (get process-name)))

(defn add-pre-proceesed
  "Add a set of pre-processed children for a
   process name."
  [ent process-name children]
  (let [procs (or (get-pre-processed ent process-name) [])
        procs (into procs children)]
    (assoc-in ent [:pre-processed process-name] procs)))

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

(defn resolve-n [selector children n]
  (cond (number? n)  (fn select-children [] (selector))
        (fn? n)      (fn select-children [] (selector (n children)))
        (symbol? n)  (try (resolve-n selector children @(ns-resolve *ns* n))
                          (catch Exception e (do (println [:failed-to-find-symbol n :in *ns*])
                                                 (throw e))))
        :else (throw (Exception. (str [:n-must-be-number-or-one-arg-fn n])))))

(defmulti process->service (fn [process-graph process] (:type process)))
;;Update: We'll allow the entity to "override" or otherwise side-step the
;;random client generation process by specifying preferences for a service.
;;If the entity ALREADY HAS a processing preferenc, then we'll eschew choosing
;;random children, and instead, select the entity's preference relative to
;;the service.
(defmethod process->service :random-children [pg {:keys [name type n service weights target] :as proc
                                                  :or {n 1}}]
  (if (= name :default-process)
    proc
    (let [process-name name
          children  (g/sinks pg name)
          selector (case (count children)
                     0 (throw (Exception. (str [proc :requires :at-least 1 :child])))
                     (child-selector (or weights children)
                                     (if (number? n) n 1)))
          random-select    (resolve-n selector children n)
          select-children  (fn entity-children
                             ([e]
                              (if-let [xs (get-pre-processed e process-name)]
                                xs
                                (random-select)))
                             ([] (random-select)))]
      (merge proc
             {:end-service
              (case service ;;only have one type of service at the moment.
                :add-children (fn add-children
                                ([ctx ent]
                                 (store/add-entity ctx
                                   (add-children-as-services
                                    (select-children ent) ent target)))
                                ([ent] (add-children-as-services
                                        (select-children ent) ent target)))
                (throw (Exception. (str [:not-implemented service]))))
              :select-children select-children
              :selector selector
              :children children}))))

;;once we have process services...
;;we can define our service network.
(defn process-map [routing-graph xs]
  (into {} (for [x xs]
             [(:name x) (if (= (:name x) :default-process) x
                            (process->service routing-graph x))])))

(defn process-based-service-network [routing-graph caps proc-map]
  (-> (services/service-net
       caps
       (for [[from to w] (g/arc-seq routing-graph)]
         {:Name from :Services to :Minutes #_w (max w 1)})
       nil)
      (vary-meta assoc :process-network true)))


;;using a baked default for now...
(defn as-default-process [service-network nd]
  (when-let [children (g/sinks service-network nd)]
    (let [selector (if (> (count children) 1)
                     #(vector (rand-nth children))
                     (let [c (first children)
                           xs [c]]
                       (fn [] xs)))]
      {:end-service 
       (fn single-child
         ([ctx ent]
          (store/add-entity ctx
                            (add-children-as-services (selector) ent)))
         ([ent] (add-children-as-services (selector) ent)))
       :children children
       :selector selector})))

;;if we have a :default-process, we'll go ahead and
;;pull it out as the default-process template, and use
;;that to define basic processes for every service
;;that doesn't have a proc..
(defn merge-processes [proc-map ctx]
  (reduce-kv (fn [acc id e]
               (store/mergee acc id e))
             ctx proc-map))

;;extract need/services from the ctx
;;that are no explicitly accounted for
;;in the proc-map
(defn extra-providers [proc-map sn]
  (into [] (filter (fn [nd] (and (not= nd :provider) (not (proc-map nd)))))
        (keys (g/nodes sn))))
;;we need to capture the entire process network.
;;that means nodes that aren't providers.
(defn merge-default-processes [proc-map ctx]
  (if-let [default (get proc-map :default-process)]
    (let [sn (services/service-network ctx)]
      (->> (for [p (services/providers ctx)
                 :when (not (:end-service p))]
                 [(:name p) (as-default-process sn (:name p))])
           (reduce (fn [acc [id xs]]
                     (store/mergee acc id xs))
                   ctx)))
    ctx))
;;we need to define providers as servicing their names...
;;This is really a hacky work around at the moment.
(defn redefine-providers [proc-map sn ctx]
  (as-> (services/providers ctx) it
    (reduce (fn [acc e]
              (store/assoce acc (:name e)
                            :services [(:name e)]))
            ctx it)
    (reduce (fn [acc id]
              (store/add-entity acc
                                (services/provider id :services [id]
                                                   :capacity (or (store/gete acc id :capacity)
                                                                 Long/MAX_VALUE))))
            it (extra-providers proc-map sn))))


(defn processes     [ctx] (store/get-domain ctx :end-service))
(defn process-groups [ctx]
  (let [psn (services/service-network ctx)]
    (for [p (store/select-entities ctx :from [:end-service :target] :where :target)]
      {:parent (:name p) 
       :children (:children p)
       :target (:target p)})))

(defn parameters    [ctx] (store/get-entity ctx :parameters))
(defn default-needs [ctx] (-> ctx parameters :default-needs))

(defn leaf-processes [ctx]
  (mapcat :children (process-groups ctx)))
           
(defn disable-leaf-processes [ctx]
  (reduce (fn [acc id]
            (store/dissoce acc id :end-service))
          ctx (leaf-processes ctx)))

(defn records->process-map [routing-graph processes]
  (let [ps (data/records->processes processes)]
    (process-map routing-graph ps)))

;;this is a work-around for generating a spec
;;for ensuring that we have expected keys in a project map.
;;The sticky point is, spec expects qualified keywords...
;;even IF we're using unqualified keys for the spec
;;option.  Weird!?
(defn qualify-keys [xs]
  (map #(keyword (str *ns*) (name %)) xs))

;;unqualified...
(def +project-keys+
  #{:tables :route-graph
    :capacities :initial-arrivals
    :service-network :parameters})

(spec/def ::project-keys +project-keys+)
;;have to inject the keys since it's a symbol, and spec is macro-based!
(eval `(spec/def ::project-map (spec/keys :req-un  ~(qualify-keys +project-keys+))))

(defn lower-case-keys [m]
  (into {} 
        (for [[k v] m]
          [(-> k  clojure.string/lower-case name) v])))

(defn xlsx->tables [path]
  (->> (io/alien->native path)
       (xl/xlsx->tables)
       (lower-case-keys)
       (keywordize-tables)
       (data/coerce-tables)))

(defn wb->proc-project [path]
  (let [tbls (xlsx->tables path)
        {:keys [routing capacities parameters processes arrivals]} tbls
        caps   (data/records->capacities capacities)
        rg     (data/records->routing-graph routing)
        params (data/records->parameters parameters)]
    {:tables           tbls
     :route-graph      rg      
     :capacities       caps      
     :initial-arrivals (when arrivals arrivals) ;;Right now...don't have anything special.
     :service-network  (process-based-service-network
                        rg
                        caps
                        nil)
     :parameters params
     :processes (records->process-map rg processes)}))

;;we'd like to move from this eventually...
(defn proc-seed-ctx
  [proj & {:keys [initial-arrivals batch->entities]
           :or {initial-arrivals {:n 10 :t 1}
                } :as opts}]
  (let [{:keys [service-network parameters processes]} proj
        {:keys [;default-behavior
                default-interarrival
                default-batch-size]} parameters
        pm     processes]

    (->> (vrc/init (store/assoce (core/debug! vrc/emptysim)
                                 :parameters :batch->entities
                                 (or batch->entities services/batch->entities))
                   :initial-arrivals initial-arrivals
                   :default-behavior beh/client-beh
                   :service-network service-network
                   :interarrival    default-interarrival
                   :batch-size      default-batch-size)
          (merge-processes pm)
          (redefine-providers pm service-network)
          (merge-default-processes pm)
          (disable-leaf-processes)
          (core/merge-entity {:parameters parameters})
          (vrc/begin-t)
          (vrc/end-t)
          )))

;;create a context with only 1 entity arrival ever.
;;useful for debugging.
(defn one-process-ctx [proj & {:keys [n] :or {n 1}}]
  (proc-seed-ctx (update proj :parameters merge {:default-batch-size 0})
                 :initial-arrivals {:n n :t 1}))


(defn run-one
    ([ctx]
     (core/debugging! (def frm (last (take-while (fn [[t c]] (not (store/gete c "1_0" :departure)))
                                                 (vrc/step-day ctx)))))))
(comment ;testing

  (def p (io/file-path "~/Documents/repat/repatdata.xlsx"))

  )
  (defn run-one
    ([ctx]
     (core/debugging! (def frm (last (take-while (fn [[t c]] (not (store/gete c "1_0" :departure)))
                                                 (vrc/step-day ctx))))))
    ([] (run-one (one-process-ctx))))

  ;;so...the original service network mapped services to leaf nodes,
  ;;which were needs.  We're encoding things differently in the
  ;;process network.  The service and needs are identical, so
  ;;they correspond to nodes on the network.
  ;;We'll basically traverse the service network until we
  ;;find a non-zero cost need/service.  

  #_(defn do-service [ent svc]  ((:end-service (store/get-entity ctx svc))
                               (assoc ent :service-plan (dissoc (get ent :service-plan {})svc))))
  ;;we should be able to simulate an entire entity's lifecycle
  ;;by walking services...
  ;;Walking a service implies invoking the selector and adding
  ;;children to an entity.
  #_(defn random-services [init-need  ent]
    ;;from an initial need, we're basically walking the service network.
    (take-while identity
                (iterate (fn [ent]
                           (let [plan (:service-plan ent)]
                             (case  (count plan)
                               0 (services/pop-plan ent)
                               1 (when-let [svc (first (keys plan))]
                                   (println [:doing-service svc])
                                   (do-service ent svc))
                               (let [_ (println [:doing-all (vec (keys plan))])
                                     _ (println [:popping-plan])]
                                 (services/pop-plan ent))))) (do-service ent init-need))))
    
  
  
;  )
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

;;Additional notes on service plans: We need the ability to define the
;;notion of lexically scoped service plans, or active plans.  As we
;;walk the service network, we may run into nodes that create a new
;;service context.  Much like 'eval,' we have a new process node that
;;requires us to traverse the children - for some definition of
;;traversal - during which all other concerns are moot.  This notion
;;of priority, or exclusion of other concerns, is similar to the
;;stack-based goal hierarchy from AI.  We're basically in a higher
;;behavior context, pursuing an immediate set of goals, and we'd like
;;to revert back to the outer context of pre-set goals when we're
;;done.

;;so, the initial notion of a service plan - really a mapping of
;;services (or goals) to needs, implies a specicif type of goal-based
;;behavior.  The entity is pursuing an an arbitrary number of goals
;;concurrently, trying to find the next available goal.  There is no
;;priority, or dependence between the goals.

;;Our more advanced context has us defining composite goals: that is,
;;pursuing a goal may imply accomplishing several other sub-goals
;;(i.e., getting a set of services).  These sub-goals may beget
;;additional sub-goals.

;;In the service network parlance, we have a parent process the
;;defines its service as adding a new service plan with one or more
;;children.  This service plan becomes the "active" plan, in which
;;case the services are completed Once the active service plan (the
;;current goals) are completed, we can revert to the outer plan...  If
;;there is no outermost plan, we are done with service.

;;We should be able to communicate a priority of service plans by have
;;a stack (or list) of plans.  The active plan is the head of the
;;list.  If there is no active plan, there is no service.

;;When a process is entered, it may now alter the service plan by
;;pushing a new plan.

;;The motivating example is for the "family services" process: it adds
;;"end family services" to the service plan, and then pushes a new
;;active plan onto the stack.  The new plan is composed of one or more
;;child nodes.

;;So, traversing our composite or "group" node boils down to pushing
;;the "end" onto the plan stack, then pushing a map of child services
;;onto the active plan.

;;We encode groupings like this:
;;  The children of a [begin ?]
;;  should be parents of [end ?]
;;In this case
;; [begin family-services]
;;  child1 child2 child3
;; [end family-services]
;;denotes a group.

;;Can we have sub-groups?
;; [begin family-services]
;;  [begin child1] child2 child3
;;  child a
;;  [end child1]
;; [end family-services]
;;So, original rules hold
;;iff the child is not indicative
;;of a compound node, (i.e.
;;an operation with a matching pair).

;;If any child is a compound node, we'd
;;be able to process its children
;;recursively.

;;tree::
;;  leaf x |
;;  [begin y]
;;  tree+
;;  [end y]

;;If we have operators, embedded in
;;the node label, then we can
;;identify these nodes easily.
;;Conversely, we can parse them
;;from a simple DSL instead of
;;having to define the graphs.
