;;A simple flow model to examine a population of entities
;;finding and receiving services to meet needs.
(ns vrcflow.core
  (:require
   [vrcflow.data :as data]
   [spork.entitysystem.store :refer [defentity keyval->component] :as store]
   [spork.util [table   :as tbl] [stats :as stats]]
   [spork.sim [simcontext     :as sim]]
   [spork.ai           [behaviorcontext :as bctx] [messaging :refer [send!! handle-message! ->msg]]
             [core :refer [debug]]]
   [spork.ai.behavior 
             :refer [beval
                     success?
                     success
                     run
                     fail
                     behave
                     ->seq
                     ->elapse
                     ->not
                     ->do
                     ->alter
                     ->elapse-until
                     ->leaf
                     ->wait-until
                     ->if
                     ->and
                     ->and!
                     ->pred
                     ->or
                     ->bnode
                     ->while
                     ->reduce
                     always-succeed
                     always-fail
                     bind!
                     bind!!
                     merge!
                     merge!!
                     push!
                     return!
                     val!
                     befn
                     ] :as b]
   [spork.cljgraph.core :as graph]
   ))
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
    ]})

(defentity provider
  "Defines a storage container for service providers. Providers may store
   more than one client."
  [id & {:keys [service capacity]}]
  {:components
   [:name     id
    :service  service
    :capacity capacity
    :clients  []]})

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

;;potentially make this the default for empty contexts.
;;just enforce the idea that our entity state lives in
;;an entity store.
(def emptysim
  "An empty simulation context with an initial start time, 
   and an empty entity store"
  (->> (assoc sim/empty-context :state store/emptystore)
       (sim/add-time 0)
       (sim/merge-entity
        {:parameters {:seed 5555
                      :wait-time 15}})))

;;We're maintaining a rule-db that maps providers to services.
;;Services are sort of the atomic bits of a service plan.

;;  A service plan is - a service - that's composed of one or more services.
;;  So service ::
;;         | Service
;;         | ServicePlan [Service]

;;In the db, we only have atomic services defined (at the moment....).


;;Screening/intake provides clients with a ServicePlan, initially
;;with a single Service.

;;As the client processes through the system, additional services may
;;be acquired based on unidentified needs...


;;Entity Behavior:
;;We'll compose complex behavior for the entity lifecycle by defining
;;simpler behaviors and going from there.

(def ^:constant +default-wait-time+ 30)

;;helper function, maybe migrate to behavior lib.
(defn alter-entity [m]
  (->do #(swap! (:entity %) merge m)))

;;this is a subset of what we have in the data; but it'll work
;;for the moment.
(def basic-needs
  "For testing purposes, provides a vector of default needs 
   we can use for random needs generation, as a function of 
   the basic network."
  (vec (indicators basic-network)))

;;this is just a shim for generating needs.
(defn random-needs
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

(defn echo [msg]
  (fn [ctx] (do (debug msg) (success ctx))))

;;Entities need to go to intake for assessment.
;;As entities self-assess, they wait in the intake.
;;Upon completing self-assessment, they meet with
;;a counselor to derive services and get routed.

;;we have a random set of needs we can derive
;;it'd be nice to have some proportional
;;representation of patients here...
;;there may be some literature from state
;;health to inform our sampling...
;;naive way is to just use a uniform distro
;;with even odds for picking a need.
;;how many needs per person?
;;make it exponentially hard to have each additional need?
(befn compute-needs {:keys [ctx entity parameters] :as benv}
 (let [needs-fn (get parameters :needs-fn random-needs)]
   (alter-entity {:needs (needs-fn)})))

;;Sets the entity's upper bound on waiting
(befn reset-wait-time {:keys [entity] :as benv}
      (alter-entity {:wait-time +default-wait-time+}))

;;If the entity has needs, derives a set of needs based on
;;services.  This is a simple projection of the needs the
;;entity presents with to the services associated with said
;;need per the input data and the service-network.
(befn compute-services {:keys [ctx entity] :as benv}
  (when-let [needs (:needs @entity)]
    (let [proposed-services (needs->services needs (:service-network @ctx))]
      (alter-entity {:needs nil
                     :services proposed-services}))))

;;find the next service we'd like to try to acquire.
;;Eventually, we'll go by priority.  For now, we'll
;;just select the next service that happens to be on our
;;chain of services...
(befn next-available-service {:keys [entity] :as benv}
      nil
      )

;#_(def screenining (->and [(get-service "screening") 
(defn set-service [nm]
  (alter-entity {:active-service nm}))  

;;enter/spawn behavior
(befn enter {:keys [entity] :as benv}
  (when (:spawning? @entity)
    (->seq [reset-wait-time
            (->do (fn [_]
                    (swap! entity merge {:spawning? nil
                                         :needs #{"Self Assessment"}})))])))

(befn wait {:keys [entity] :as benv}
      (echo "waiting....") )

(defn set-active-service [svc]
  (->alter #(assoc % :active-service svc)))

;;Prepare the entity's service plan based off of its needs.
;;If it already has one, we're done.
(befn get-service-plan {:keys [entity ctx] :as benv}
  (let [ent  @entity]
    (if (:service-plan ent)
      (echo (str (:name ent) " has a service plan"))
      (let [_    (debug (str "computing service plan for "
                             (select-keys ent [:name :needs])))
            net  (store/gete @ctx :parameters :service-network)
            plan (service-plan (:needs ent) net)
            _    (debug (str (:name ent) " wants to follow " plan))]
        (->do (fn [_] (swap! entity assoc :service-plan plan)))))))

;;Entities going through screening are able to compute their needs
;;and develop a service plan.
(befn screening {:keys [ctx] :as benv}
      (->seq [(set-service "screening")
              compute-needs
              get-service-plan
              wait]))

;;Register the entity's interest and time-of-entry.
;; (befn request-service {:keys [entity ctx] :as benv}
;;       ;;entity now has a service plan, and advertises
;;       ;;interest (along with time-in) for each service.
;;       ;;This establishes entity's place in multiple queues.
;;       (let [ent  @entity
;;             plan (:service-plan ent)]
;;         (->do #(swap! ctx store/updatee :service-requests 
;;                       )

;;find-services
;;  should we advertise all services we're interested in?
;;  or go by entity-priority?

;;if we have an active service set that we're trying to
;;get to, we already have a service in mind.
;;If not, we need to consult our service plan.
(befn find-service {:keys [entity] :as benv}
      (if-let [active-service (:active-service @entity)]
        (->seq [(echo (str "found active service: "  active-service))
                (set-active-service active-service)])
        (->seq [(echo "looking for services")
                get-service-plan
                #_set-next-available-service])))

(def client-beh
  (->seq [(echo "client-updating")
          enter
          find-service]))

(comment


;;get-service
(def get-service
  (->seq [move-to-service
          reset-wait-time
          reset-service-time
          wait-in-service]))

#_(def await-service
    (->and [has-wait-time?
            register-service
            move-to-waiting
            wait]))

;;registering for a service puts the entity in a queue for said service.
;;services are effectively resources.
;;When we leave a service, we notify the next entity (iff the entity is
;;waiting) that the service is available.
;;This models the service as a resource.
(befn register-service ^behaviorenv {:keys [ctx next-service statedata] :as benv}
      (when next-service
        ;;entity should be put on the waiting list, notified when the service is next available.        
        )
      )

(befn should-move? ^behaviorenv {:keys [next-position statedata] :as benv}
      (when (or next-position
                (zero? (fsm/remaining statedata)) ;;time is up...
                (spawning? statedata))
        (success benv)))

)

;;generate an update for itself the next time...
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
  ([t f b] (assoc (next-batch t f) :behavior b)))
  
(defn batch->entities [{:keys [n t behavior] :as batch :or {behavior echo}}]
  (for [idx (range n)]
    (let [nm (str t "_" idx)]
      (merge (client nm :arrival t)
             {:behavior behavior
              :spawning? true}
             ))))

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
  (->> (store/assoce ctx :arrival :pending batch)
       (sim/request-update (:t batch) :arrival :arrival)))

(defn handle-arrivals
  "Pulls out the next arrival batch from the :arrival entity, 
   consuming the update in the process."
  [t new-entities ctx]
  (-> ctx 
      (sim/drop-update :arrival t :arrival) ;eliminate current update.
      (store/add-entities new-entities) ;;add new entities.
      (sim/add-updates             ;;request updates.
       (for [e new-entities]
         [t (:name e) :client]
         )))) 

(defn update-by
  "Aux function to update via specific update-type.  Sends a default :update 
   message."
  [update-type ctx]
  (let [t (sim/current-time ctx)]
    (->> (sim/get-updates update-type t ctx)
         (keys)
         (reduce (fn [acc e]
                   (println [:updating e])
                   (send!! (store/get-entity acc e) :update t acc)) ctx))))

;;Simulation Systems
;;==================
;;Since we're simulating using a stochastic process,
;;we will unfold a series of arrival times using some distribution.
;;We need something to indicate arrivals eventfully.

(defn init
  "Creates an initial context with a fresh distribution, schedules initial
   batch of arrivals too."
  ([ctx & {:keys [default-behavior service-network]
           :or {default-behavior client-beh
                service-network basic-network}}]
   (let [dist        (stats/exponential-dist 5)
         f           (fn interarrival [] (long (dist)))]
     (->>  ctx
           (sim/merge-entity  {:arrival {:arrival-fn f}
                               :parameters {:default-behavior default-behavior
                                            :service-network  service-network}})
           (schedule-arrivals (next-batch (sim/get-time ctx) f default-behavior)))))
  ([] (init emptysim)))

;;A) compute next arrivals.
;;B) handle current arrivals.
;;  note: arrivals can be batches, i.e. arbitrary size.
;;        we can default to 1.
;;
;;We need to maintain a few things:
;;   {:pending-arrivals [should be consistent with updates]}
;;        

;;we can add these to simcontext.
;;possible short-hand for defining
;;recurring activities.
#_(activity :arrival-manager
            :schedule      (exponential 3) 
            ;;entity update behavior
            :behavior      (->seq [schedule-next-arrivals
                                   handle-current-arrivals])
            ;;responds to events..when events are fired, we forward
            ;;them to the behavior
            :routing       {:arrival handle-arrivals})

;;we'll encode arrivals as updates of type :arrival
(defn process-arrivals
  "The arrivals system processes batches of entities and schedules more arrival
   updates.  batch->entities should be a function that maps a batch, 
   {:t long :n long} -> [entity*]"
  ([batch->entities ctx]
   (if-let [arrivals? (seq (sim/get-updates :arrival (sim/current-time ctx) ctx))]
     (let [_      (spork.ai.core/debug "[<<<<<<Processing Arrivals!>>>>>>]")
           arr    (store/get-entity ctx :arrival) ;;known entity arrivals...
           {:keys [pending arrival-fn]}    arr
           new-entities (batch->entities pending)
           new-batch    (next-batch (:t pending) arrival-fn
                                    (store/gete ctx :parameters :default-behavior))]
       (->> ctx
            (handle-arrivals (:t pending) new-entities)
            (schedule-arrivals new-batch)))
     (do (spork.ai.core/debug "No arrivals!")
         ctx)))
  ([ctx] (process-arrivals batch->entities ctx)))


;;Update Clients [notify clients of the passage of time, leading to
;;clients leaving services, registering with new services, or preparing to leave.
;;We can just send update messages...
(defn update-clients [ctx] (update-by :client ctx))

;;Update Services [Services notify registered, non-waiting clients of availability,
;;                 clients move to services]
;;List newly-available services.
(defn update-services  [ctx] (update-by :service ctx))

;;For newly-available services with clients waiting, allocate the next n clients
;;based on available capacity.
(defn allocate-services [ctx] )

;;Any entities with a :prepared-to-leave component are discharged from the system,
;;recording statistics along the way.
(defn finalize-clients [ctx]  )

;;Note: we "could" use dataflow dependencies, ala reagent, to establish a declarative
;;way of relating dependencies in the simulation.



;;Notes // Pending:
;;Priority rules may be considered (we don't here).  It's first-come-first-serve
;;priority right now.  Do they matter in practice?  Is there an actual
;;triage process?


(comment  ;testing

 (def isim   (init))
 (def isim1  (sim/advance-time isim ))
 ;;arrivals happen
 (def isim1a (process-arrivals isim1))
 (sim/get-updates :client (sim/get-time isim1a) isim1a)
 (binding [spork.ai.core/*debug* true] (def res (update-clients isim1a)))
; (def asim (sim/advance-time isim1a))

  )
