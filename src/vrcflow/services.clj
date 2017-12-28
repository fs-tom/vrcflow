;;API for our simulation state and other
;;common functions.
(ns vrcflow.services
    (:require
   [vrcflow [data :as data] [actor :as actor]]
   [spork.entitysystem.store :refer [defentity keyval->component] :as store]
   [spork.util [table   :as tbl] [stats :as stats] [general :as gen]]
   [spork.sim  [core :as core] [simcontext :as sim] [history :as history]]
   [spork.ai   [behaviorcontext :as bctx] [behavior]
               [messaging :refer [send!! handle-message! ->msg]]
               [core :refer [debug]]]
   [spork.cljgraph.core :as graph]
   ))

(set! *print-level* 5)
(set! *print-length* 100)

;;Generic client processing algorithm.

;;Assuming a have a client entity, with a
;;pre-determined list of needs and an arrival
;;time.

(defentity client
  "Provides a constructor for building clients."
  [id & {:keys [needs arrival exit services]}]
  {:components
   [:name    id
    :needs   needs 
    :arrival arrival
    :exit    exit
    :services []
    :client-entity true
    ]})

(defentity provider
  "Defines a storage container for service providers. Providers may store
   more than one client."
  [id & {:keys [services capacity]}]
  {:components
   [:name     id
    :services  services
    :capacity capacity
    :clients  []
    :provider-entity true]})

(defn service-network [db] (store/gete db :parameters :service-network))
(defn clients   [db] (store/select-entities db :from :client-entity))
(defn service-plans [db] (map (juxt :name :service-plan) (clients db)))
(defn providers [db] (store/select-entities db :from :provider-entity))
(defn active-providers [db] (filter (comp seq :clients) (providers db)))
(defn service-time [db provider svc]
  (graph/arc-weight (service-network db) provider svc))    
;;Update:
;;We need to be able to push service plans.
;;We'll add a component: :plan-stack
;;:service-plan is logically the head of the stack,
;;and is not "stored" there.
;;We'll define operations.
(defn push-plan
  "Establishes a new service plan as the top
   of the plan stack.  Moves any extant service-plan
   to the plan-stack for later retrieval. Two-arg
   version works on entity maps."
  ([db id plan]
   (if (empty? (store/gete db id :service-plan))
     (store/assoce db id :service-plan plan)
     (-> db
         (store/assoce id :plan-stack
                       (conj (or (store/gete db id :plan-stack) '())
                             (store/gete db id :service-plan)))
         (store/assoce id :service-plan plan))))
  ([ent plan]
   (if (empty? (get ent :service-plan))
     (assoc ent :service-plan plan)
     (-> ent
         (assoc :plan-stack
                       (conj (or (get ent :plan-stack) '())
                             (get ent :service-plan)))
         (assoc :service-plan plan)))))

(defn pop-plan
  "Examines the plan-stack, setting the first plan
   as the service-plan and removing it from the plan-stack.
   Two-arg version works on entity maps."
  ([db id]
   (if-let [ps (some-> db (store/gete id :plan-stack) seq)]
     (-> db
         (store/assoce id :plan-stack   (pop ps))
         (store/assoce id :service-plan (first ps)))
     db))
  ([ent]
   (if-let [ps (some-> ent  :plan-stack seq)]
     (-> ent
         (assoc  :plan-stack (pop ps))
         (assoc :service-plan (first ps)))
     ent)))

(defn service-remaining?
  "Determines if the entity has either an active
   service plan, or a pending service plan in the
   plan-stack.  one-arg version works with entity
   maps."
  ([db id]
   (or  (not (empty? (store/gete db id :service-plan)))
        (not (empty? (store/gete db id :plan-stack)))))
  ([ent]  (or  (not (empty? (get ent :service-plan)))
               (not (empty? (get ent :plan-stack))))))

;;So, service-providers can serve up to capacity clients....
;;When a client is undergoing a service, it consumes
;;capacity and takes up a slot. [typical]

;;The basic model here is that needs transform into
;;services.

;;If a client has needs, it needs to try to find out
;;how to transform them into services.

;;Once a client figures out the services for its
;;highest-priority need, it will then try to receive
;;said services.

;;Initially, clients will self-screen, that is,
;;map their needs to services.

;;Later, we'll create a screening resource, which
;;will map needs to services via consultation.
;;  - The client (walk-in) 

;;- What is the highest priority entity?  (determined by time)
;;  - What is the highest priority need?  (determined by static weights?)
;;      [
;;    - What services are available to meet the
;;      entity's need?
;;    -  Does the service meet the need?
;;        - Is there a referral? [Hidden Needs]
;;    -  What happens when needs are met?
;;       - Positively change metrics?
;;    -  What happens when needs are not met?
;;       - Client waits?
;;         - how long?
;;       - tries to fill next need?
;;       - Leaves (permanently?)

;;services and capacities give us an abstract layout of the resources
;;available for providing services.

;;Modeling Services
;;=================
;;One way to do this is to build a service network.
;;We could also just push services into the entity store.


;;A service provider has a finite number of slots, governed by capacity.
;;Client 

;;A service is provided by someone.

;;Services have associated functions for serving a client.
;;This is a way to implement referrals, and subsequent
;;complex processing chains.
;;  Or explicit service chains.


;;How long do services take?
;;Need data...
(defn service-net
  "Creates a service network that maintains the hierarchical
  relationship - and temporal relations - between indicators,
  services, and service providers.  Maintains sets of metadata for
  indicators, services, and providers to support relational queries.
  Requires input sequences of capacity records, service records, and
  targeting prompt records."
  ([caps svcs prompts]
   (let [nodes     caps
         providers (for [r nodes]
                     [:provider (:Name r)])
         arcs      (for [r svcs]
                     [(:Name r) (:Services r) (:Minutes r)])
         services nil #_(for [nd (map secondt arcs)]
                          [:service nd])
         targets  (for [r prompts]
                    [(:Service r) (:Target r)])
         indicators  nil #_(for [nd (map first targets)]
                             [:indicator nd])]
     (-> (reduce (fn [acc nd]
                   (graph/conj-node acc (:Name nd) nd))
                graph/empty-graph nodes)
         (graph/add-arcs arcs)
         (graph/add-arcs targets)
         (graph/add-arcs (concat services indicators providers))
         (with-meta {:services   (distinct (map second arcs))
                     :providers  (distinct (map second providers))
                     :indicators (distinct (map second targets))}))))
  ([] (service-net (tbl/table-records data/cap-table)
                   (tbl/table-records data/svc-table)
                   (tbl/table-records data/prompt-table))))

(def basic-network (service-net))
;;Service network API
;;===================

;;Services are children of :service
(defn services       [sn]  (:services (meta sn)))
;;indicators are children of :indicator
(defn indicators     [sn]  (:indicators (meta sn)))
(defn need->services [need service-net]
  (graph/sources service-net need))

(defn service->providers [service service-net]
  (graph/sources service-net service))

(defn capacities [service-net]
  (into {}
        (for [nd (vals (graph/nodes service-net))
              :when (:Capacity nd)]
          [(:Name nd) (:Capacity nd)])))

(defn service-plan
  "A service plan consists of building a reduced map of 
   needs to services.  This provides a basis for addressing
   the client's needs via services.  The client then 
   tries to find an available service to meet a need.
   As services are acquired, needs are met."
  [needs service-net]
  (let [svcs->needs (map #(vector (need->services % service-net) %) needs)]
    (reduce (fn [acc [svcs needs-addressed :as s]]              
              (assoc acc (if (= (count svcs) 1)
                           (first svcs)
                           svcs) needs-addressed))
            {} svcs->needs)))

(defn service-tree [service-network]
  (->> (keys (capacities service-network))
       (map #(into  [%] (graph/sinks service-network %)))
       (reduce (fn [acc [k svc]]
                 (assoc acc k (conj (get acc k []) svc))) {})))
          
(defn service-network->provider-entities [service-network]
  (let [tree (service-tree service-network)]
    (for [nd (vals (graph/nodes service-network))
          :when (:Capacity nd)]    
      (provider (:Name nd)
                :services (tree (:Name nd))
                :capacity (:Capacity nd)))))

(defn register-providers [service-network ctx]
  (->> service-network
       (service-network->provider-entities)
       (store/add-entities ctx)))



;;We're maintaining a rule-db that maps providers to services.
;;Services are sort of the atomic bits of a service plan.

;;  A service plan is - a service - that's composed of one or more services.
;;  So service ::
;;         | Service
;;         | ServicePlan [Service]

;;In the db, we only have atomic services defined (at the moment....).

;;We can either specify a composite service plan, or use referrals.
;;probably just easier to specify referrals...
;;Where do we define referrals?
;;  There's some policy graph with transition probabilities.
;;  This infers process definitions.
;;  When an entity is processing, we can look to see if
;;  there are defined transitions it should follow.
;;  If so, that becomes the active need.





;;Screening/intake provides clients with a ServicePlan, initially
;;with a single Service.

;;As the client processes through the system, additional services may
;;be acquired based on unidentified needs...


;;Entity Behavior:
;;We'll compose complex behavior for the entity lifecycle by defining
;;simpler behaviors and going from there.

(def ^:constant +default-wait-time+ 30)

;;this is a subset of what we have in the data; but it'll work
;;for the moment.
(def basic-needs
  "For testing purposes, provides a vector of default needs 
   we can use for random needs generation, as a function of 
   the basic network."
  (vec (filter #(not (#{"Where can I Wait?" "Self Assessment" "Where do I go?"} %))
               (indicators basic-network))))

;;this is just a shim for generating needs.
(defn random-needs ;;called from behavior.
  ([] (random-needs 2))
  ([needs n]
   (reduce (fn [acc x]
             (if (acc x) acc
                 (let [nxt (conj acc x)]
                   (if (== (count nxt) n)
                     (reduced nxt)
                     nxt))))
           #{}
           (repeatedly #(rand-nth needs))))
  ([n] (random-needs basic-needs n)))

(defn get-in-line [db id svcs]
  (let [waiting (store/get-entity db :waiting-list)]
    (->> svcs
         (reduce (fn [acc svc]
                   (let [line (get acc svc [])]
                     (assoc acc svc (conj line id))))
                 waiting)
         (store/mergee db :waiting-list))))

;;generate an update for itself the next time...
;;next-batch now accepts a size
(defn next-batch
  "Given a start time t, and an interarrival time
   function f::nil->number, generates a map of 
   {:n arrival-count :t next-arrival-time} where t 
   is computed by sampling from f, such that the 
   interarrival time is non-zero.  Zero-values 
   are aggregated into the batch via incrementing
   n, accounting for concurrent arrivals (i.e. batches)."
  ([t f]
   (loop [dt (f)
          acc 1]
     (if (pos? dt)
       {:n acc :t (+ dt t)}
       (recur (f) (inc acc)))))
  ([t f b] (assoc (next-batch t f) :behavior b))
  ([t f size-f b]
     (-> (loop [dt (f)
                acc (size-f)]
           (if (pos? dt)
             {:n acc :t (+ dt t)}
             (recur (f) (+ acc (size-f)))))
         (assoc  :behavior b))))

(comment
  (defn test-sample []
    (let [dist (spork.util.stats/exponential-dist 5)
          f (fn [] (long (dist)))
          size (spork.util.stats/triangle-dist 1 10 20) ;;better distribution...
          s (fn [] (long (size)))
          clck (atom 0)]
    (for [i (range 100)]
      (let [b (services/next-batch @clck  f s :blah)
            _ (reset! clck (:t b))]
        b))))

  (def samples (reductions (fn [acc [n t]]
                             {:t t :n n :total (+ n (:total acc))})
                           {:t 0 :n 0 :total 0}
                           (map (juxt :n :t) xs)))
  (i/view (c/xy-plot (map :t samples) (map :total samples)))
  ;;should give us about 1000 people/day
  ;;where 480min/day * 1 arrival / 5 min = 96 arrivals/day
  ;;96 arrivals/day * 10 entities/arrival  = 960 entities/day
  )
(defn batch->entities [{:keys [n t behavior] :as batch
                        :or {behavior (spork.ai.behavior/always-fail "no-behavior!")}}]
  (for [idx (range n)]
    (let [nm (str t "_" idx)]
      (merge (client nm :arrival t)
             {:behavior behavior
              :spawning? true}
             ))))

(defn ensure-behavior [ctx batch]
  (if (:behavior batch)
    batch
    (assoc batch :behavior
       (store/gete ctx :parameters :default-behavior))))

;;Note: we can handle arrivals a couple of different ways.
;;The way I'm going here is to have a central entity that
;;batches updates.  We have an explicit arrival process
;;in our simulation system.  This assumes we never have
;;arrivals on the same day (we could do something like that
;;though, we just don't necessarily advance time if
;;unplanned arrivals occur).
(defn schedule-arrivals
  "Given a batch order, schedules new arrivals for ctx."
  [batch ctx]
  (->> batch
       (ensure-behavior ctx)
       (store/assoce ctx :arrival :pending)
       (sim/request-update (:t batch) :arrival :arrival)))

;;;temporary hack/shim...
(defn add-updates [ctx xs]
  (reduce (fn [acc [t from type]]
            (sim/request-update t from type acc))
          ctx xs))

(defn handle-arrivals
  "Pulls out the next arrival batch from the :arrival entity, 
   consuming the update in the process."
  [t new-entities ctx]
  (-> ctx 
      (sim/drop-update :arrival t :arrival) ;eliminate current update.
      (store/add-entities new-entities) ;;add new entities.
      ;;TODO: fix add-updates in spork.sim.simcontext....we're getting transient
      ;;problems!
      (add-updates             ;;request updates.
       (for [e new-entities]
         [t (:name e) :client]
         )))) 

(defn add-client [provider id ctx]
  (store/updatee ctx provider :clients conj id))

(defn drop-client [provider id ctx]
  (store/updatee ctx provider
    :clients #(into [] (filter (fn [x] (not= x id))) %)))

(defn wait-at [id provider wait-time ctx]
  (send!! (store/get-entity ctx id)
          :wait
          {:location provider
           :wait-time wait-time} ctx))

(defn begin-service [provider id ctx]
  (let [f (store/gete ctx provider :begin-service)]
    (if-not f
      ctx
      (f ctx (store/get-entity ctx id)))))

(defn end-service [provider id ctx]
  (let [f (store/gete ctx provider :end-service)]
    (if-not f
      ctx
      (f ctx (store/get-entity ctx id)))))

(defn allocate-provider
  "Allocate the entity to the provider's service.  If the provider
   has a begin-service function associated, it will be applied to the
   entity during allocation."
  [ctx provider svc id]
  (let [wait-time (service-time ctx provider svc)]
    (debug [:assigning id :to svc :for wait-time (dissoc (store/get-entity ctx id) :behavior)])
    (->> (store/mergee ctx id {:active-service svc :unoccupied false})
         (add-client provider id)
         (wait-at id provider wait-time)
         (begin-service provider id)
         (sim/trigger-event :acquired-service id provider
           (core/msg "Entity " id " being served for "
                     svc " by " provider) nil)
         )))

(defn deallocate-provider
  "Allocate the entity to the provider's service.  If the provider
   has an end-service function associated, it will be applied
   during deallocation."
  [ctx provider id]
  (debug [:removing id :from provider])
  (->> (end-service provider id ctx)
       (drop-client provider id)
       (sim/trigger-event :left-service id provider
         (core/msg "Entity " id " left " provider) nil)
       ))

;;generalized from hardcoded VRC implementation.
(def get-waiting-area
  (gen/memo-1
   (fn get-waiting-area [ctx]
     (or (store/gete ctx :parameters :default-wait-location)
         "VRC Waiting Area")
     )))

;;TODO: change this to include 
;;when a client is in waiting, we update the client...
(defn waiting-service [ctx id]
  (-> ctx 
      (allocate-provider  (get-waiting-area ctx) #_"VRC Waiting Area" "Waiting" id)
      (store/assoce id :unoccupied true)))

(defn needs-service?
  [e]
  (or (not (:active-service e))
      (= (:active-service e) "Waiting")))

(defn wants-service?
  [e svc]
  (get (:service-plan e) svc))

;;if the entity is not in active service, it's eligible.
;;ineligible entities will be pruned transitively (they
;;may have acquired service in the mean-time...
(defn service-eligible?
  ([svc e]
   (and (needs-service? e)
        (wants-service?  e svc))))

(defn current-capacity [provider]
  (- (:capacity provider) (count (:clients provider))))

(defn utilization [provider]
  (/ (count (:clients provider)) (:capacity provider)))
 
(defn available-service [svc ctx]
  (when-let [providers (service->providers svc
                          (store/gete ctx :parameters :service-network))]
    (->> providers         
         (map (juxt identity #(current-capacity (store/get-entity ctx %))))
         (filter (comp pos? second)))))

(defn not-allocated
  "Return a sequence of entities that have no active-service 
   allocated."
  [ctx]
  (->> (store/select-entities ctx :from [:active-service])
       (filter (fn [e] (and (not (:active-service e))
                            (not (:departure e))
                            (pos? (:wait-time e)))))
       (map :name)))

(defn departures [ctx] (keys (store/get-domain ctx :departure)))

(defn wait-capacity
  "Returns the amount of spaces we have for folks to sit and wait.."
  [ctx]
  (current-capacity
   (store/get-entity ctx "VRC Waiting Area")))

