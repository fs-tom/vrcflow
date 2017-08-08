;;A simple flow model to examine a population of entities
;;finding and receiving services to meet needs.
(ns vrcflow.core
  (:require
   [vrcflow [data :as data] [actor :as actor] #_[behavior :as beh]]
   [spork.entitysystem.store :refer [defentity keyval->component] :as store]
   [spork.util [table   :as tbl] [stats :as stats]]
   [spork.sim  [core :as core] [simcontext :as sim] [history :as history]]
   [spork.ai   [behaviorcontext :as bctx]
               [messaging :refer [send!! handle-message! ->msg]]
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
  (->> (keys (capacities basic-network))
       (map #(into  [%] (graph/sinks basic-network %)))
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

;;potentially make this the default for empty contexts.
;;just enforce the idea that our entity state lives in
;;an entity store.
(def emptysim
  "An empty simulation context with an initial start time, 
   and an empty entity store"
  (->> core/emptysim
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
  (vec (filter #(not (#{"Where can I Wait?" "Self Assessment" "Where do I go?"} %))
               (indicators basic-network))))

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
#_(befn compute-services {:keys [ctx entity] :as benv}
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
(befn enter {:keys [entity ctx] :as benv}
  (when (:spawning? @entity)
    (->seq [reset-wait-time
            (echo (str [:client-arrived (:name @entity) :at (core/get-time @ctx)]))
            (->do (fn [_]
                    (swap! entity merge {:spawning? nil
                                         :needs #{"Self Assessment"}})))])))


#_(befn update-after  ^behaviorenv [entity wait-time tupdate ctx]
   (when wait-time
     (->alter
      #(if (effectively-infinite? wait-time)
         (do (debug [(:name @entity) :waiting :infinitely]) ;skip requesting update.             
             (dissoc % :wait-time)
             ) 
         (let [tfut (+ tupdate (ensure-pos! wait-time))
               e                       (:name @entity)
               _    (debug [e :requesting-update :at tfut])]
           (swap! ctx (fn [ctx] 
                         (core/request-update tfut
                                              e
                                              :supply-update
                                              ctx)))
           (dissoc % :wait-time) ;remove the wait-time from further consideration...           
           )))))

(declare screening)
(defn wait-beh [location wait-time]
  (->and [(echo "waiting....")
          (alter-entity {:location location
                         :wait-time wait-time})
          (->do (fn [{:keys [tupdate entity ctx] :as benv}]
                  (let [tfut (+ tupdate wait-time)
                        e                       (:name @entity)
                        _    (debug [e :requesting-update :at tfut])]
                    (swap! ctx (fn [ctx] 
                                 (core/request-update tfut
                                                      e
                                                      :client
                                                      ctx))))))
          screening]
         ))
                   

(defn wait-at [id provider wait-time ctx]
  (send!! (store/get-entity ctx id)
          :wait
          {:location provider
           :wait-time wait-time} ctx))

(defn set-active-service [svc]
  (->alter #(assoc % :active-service svc)))

(befn set-departure {:keys [entity tupdate] :as benv}
      (->seq [(echo "scheduling departure!")
              (->do (fn [_] (swap! entity assoc :departure tupdate)))]
      ))

;;Prepare the entity's service plan based off of its needs.
;;If it already has one, we're done.
(befn get-service-plan {:keys [entity ctx] :as benv}
  (let [ent  @entity]
    (if-let  [plan (:service-plan ent)]
      (if (pos? (count plan))
        (echo (str (:name ent) " has a service plan"))
        (->seq [(echo (str (:name ent) " completed service plan!"))                
                set-departure]))
      (let [_    (debug (str "computing service plan for "
                             (select-keys ent [:name :needs])))
            net  (store/gete @ctx :parameters :service-network)
            plan (service-plan (:needs ent) net)
            _    (debug (str (:name ent) " wants to follow " plan))]
        (->do (fn [_] (swap! entity assoc :service-plan plan)))))))

;;Entities going through screening are able to compute their needs
;;and develop a service plan.
(befn screening {:keys [entity ctx] :as benv}
      (if (= (:active-service @entity) "Screening")
        (->seq [(echo "screening")
                compute-needs
                (alter-entity {:service-plan nil})
                get-service-plan
                ])
        (success benv)))

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

(defn get-in-line [db id svcs]
  (let [waiting (store/get-entity db :waiting-list)]
    (->> svcs
         (reduce (fn [acc svc]
                   (let [line (get acc svc [])]
                     (assoc acc svc (conj line id))))
                 waiting)
         (store/mergee db :waiting-list))))

(befn request-next-available-service {:keys [entity ctx] :as benv}
      (let [ent @entity
            plan  (:service-plan ent)
            svcs  (keys plan)]
      (when (seq plan) ;;entity has services...
        (debug [(:name ent) :requesting-services svcs])
        (->alter (fn [benv]
                   (do
                      (swap! ctx get-in-line (:name ent) svcs)
                      benv))))))

(befn age {:keys [tupdate entity] :as benv}
 (let [ent @entity
       wt  (:wait-time ent)
       tprev (or (:last-update ent) tupdate)
       dt (- tupdate tprev)]
   (if (pos? dt)
     (do (debug (str "aging entity " dt))
         (alter-entity
          {:wait-time (- wt (- tupdate tprev))}))
     (success benv))))

(declare deallocate-provider)
;;Make sure we remove the service from the service-plan.
(befn finish-service {:keys [ctx entity] :as benv}
 (let [ent @entity
       active-service (:active-service ent)]
   (->seq [(echo (str "Entity finished service: " active-service))
           (alter-entity {:active-service nil
                          :location nil
                          :service-plan (dissoc (:service-plan ent) active-service)})
           (->do (fn [_]
                   (swap! ctx
                          deallocate-provider
                          (:location ent) ;provider
                          (:name ent))))])))

;;if we have an active service set that we're trying to
;;get to, we already have a service in mind.
;;If not, we need to consult our service plan.
(befn find-service {:keys [tupdate entity] :as benv}
      (if-let [active-service (:active-service @entity)]
        (let [ent @entity
              wt (:wait-time ent)]
          (if (zero? wt)
            (->seq [finish-service
                    get-service-plan
                    request-next-available-service
                    reset-wait-time])
            (echo "Still in service")))
        
        (->seq [(echo "looking for services")
                get-service-plan
                request-next-available-service
                reset-wait-time])))

(def client-update-beh
  (->seq [(echo "client-updating")
          enter
          age
          find-service]))

(defn ->trigger [id from to msg-form data]
  (fn [benv]
    (let [ctx @(:ctx benv)
          _   (reset! (:ctx benv)
                      (core/trigger-event id from to msg-form data ctx))]
      (success benv))))

(def leaving-beh
  (->seq [(echo "leaving!")          
          (->do (fn [benv]
                  (let [ent @(:entity benv)]
                    (swap! (:ctx benv)
                           #(core/trigger-event :leaving 
                                                (:name ent) 
                                                :anyone
                                                (str (:name ent) :left)
                                                nil
                                                %)
                           )
                    (swap! (:entity benv) merge {:departure (:tupdate benv)
                                                 :wait-time 0}))))]
         ))

;;update behavior is governed by 
(def default-handler
  (actor/->handler
   {:update (->and [(echo :update)
                    client-update-beh                           
                    ]) 
    :wait   #(let [msg (:current-message %)
                   {:keys [location wait-time]} (:data msg)
                   ;; _ (when (= (:name @(:entity %)) "1_6")
                   ;;     (println [:waiting (dissoc @(:entity %) :behavior))))
                   ]
               (wait-beh location wait-time))
    :leave  leaving-beh}))

(def client-beh
  (actor/process-messages-beh default-handler)
  #_(->seq [
          client-update-beh]))

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
  
(defn batch->entities [{:keys [n t behavior] :as batch
                        :or {behavior (always-fail "no-behavior!")}}]
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

(defn update-by
  "Aux function to update via specific update-type.  Sends a default :update 
   message."
  [update-type ctx]
  (let [t (sim/current-time ctx)]
    (->> (sim/get-updates update-type t ctx)
         (keys)
         (reduce (fn [acc e]
                   (send!! (store/get-entity acc e) :update t acc))
                   ctx))))

;;Service operations
;;==================
(defn service-network->entities [sn])

;;Simulation Systems
;;==================
;;Since we're simulating using a stochastic process,
;;we will unfold a series of arrival times using some distribution.
;;We need something to indicate arrivals eventfully.

(defn init
  "Creates an initial context with a fresh distribution, schedules initial
   batch of arrivals too."
  ([ctx & {:keys [default-behavior service-network initial-arrivals]
           :or {default-behavior client-beh
                service-network basic-network}}]
   (let [dist        (stats/exponential-dist 5)
         f           (fn interarrival [] (long (dist)))]
     (->>  ctx
           (sim/merge-entity  {:arrival {:arrival-fn f}
                               :parameters {:default-behavior default-behavior
                                            :service-network  service-network}})
           (schedule-arrivals (or initial-arrivals
                                  (next-batch (sim/get-time ctx) f default-behavior)))
           (register-providers service-network)
           )))
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
     (let [_      (debug "[<<<<<<Processing Arrivals!>>>>>>]")
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

(defn current-capacity [provider]
  (- (:capacity provider) (count (:clients provider))))
 
(defn available-service [svc ctx]
  (when-let [providers (service->providers svc
                          (store/gete ctx :parameters :service-network))]
    (->> providers         
         (map (juxt identity #(current-capacity (store/get-entity ctx %))))
         (filter (comp pos? second)))))

(defn fill-to
  "Given an amount of fill and multiple pairs of 
   [service capacity], will return a sequence of 
   [service amount-filled fill-remaining], 
   until fill-remaining is 0 or xs is exhausted."
  [n xs]
  (when-let [x (first xs)]
    (let [[nm c] x]
      (if (<= n c)
        (let [remaining (- c n)
              removed   (- c remaining)]
          [[nm removed 0]])
        (lazy-seq
         (cons [nm c (- n c)]
               (fill-to (- n c) (rest xs))))))))

(defn pop-waiting-list [ctx svc wl n]
  (if (== n (count wl))
      (store/dissoce ctx :waiting-list svc)
      (store/assoce ctx :waiting-list svc
                    (into [] (subvec wl n)))))

(defn add-client [provider id ctx]
  (store/updatee ctx provider :clients conj id))

(defn drop-client [provider id ctx]
  (store/updatee ctx provider
    :clients #(into [] (filter (fn [x] (not= x id))) %)))

;;when clients engage in a service, they end up waiting.
#_(defn service->behavior [svc]
  (case svc
    "Screening" screening
    wait))

(defn allocate-provider
  "Allocate the entity to the provider's service,"
  [ctx provider svc id]
  (let [wait-time (service-time ctx provider svc)]
    (debug [:assigning id :to svc :for wait-time (dissoc (store/get-entity ctx id) :behavior)])
    (->> (store/mergee ctx id {:active-service svc :unoccupied false})
         (add-client provider id)
         (wait-at id provider wait-time)
         (sim/trigger-event :acquired-service id provider
           (core/msg "Entity " id " being served for "
                     svc " by " provider) nil)
         )))

(defn deallocate-provider
  "Allocate the entity to the provider's service,"
  [ctx provider id]
  (debug [:removing id :from provider])
  (->> (drop-client provider id ctx)
       (sim/trigger-event :left-service id provider
         (core/msg "Entity " id " left " provider) nil)
       ))

;;when a client is in waiting, we update the client...
(defn waiting-service [ctx id]
  (-> ctx 
      (allocate-provider  "VRC Waiting Area" "Waiting" id)
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

;;when we assign an entity to a service, we need to
;;eliminate him from the waiting lists; or otherwise
;;mark him as unavailable.
(defn assign-service [ctx svc ents provider-caps]
  (let [ents   (filterv (comp (partial service-eligible? svc)
                              #(store/get-entity ctx %))  ents)
        deltas (fill-to (count ents) provider-caps)      
        idx    (atom 0)
        assign-ent (fn [ctx provider]
                     (let [id (nth ents @idx)
                           _  (reset! idx (inc @idx))]
                           (allocate-provider ctx provider svc id)))]
    ;(->
     (reduce (fn blah [acc [provider n remaining]]
               #_(println n)
               (reduce (fn [acc _] (assign-ent acc provider)) acc (range n)))
             ctx deltas)
     #_(pop-waiting-list svc ents @idx))
  ;)
)

                 
;;note: some entities may no longer care about services...
;;Assume, for now, that the waiting list is up to date,
;;i.e. occupied entities are pruned out...
;;Note: at the most, we'll have 28 entities waiting...
(defn fill-services
  ([waiting ctx]
   (reduce-kv (fn [acc svc ents]
                (when-let [xs (available-service svc ctx)] 
                  (assign-service acc svc ents xs)))
              ctx waiting))
  ([ctx]
   (fill-services (store/get-entity ctx :waiting-list) ctx)))

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


;;Update Clients [notify clients of the passage of time, leading to
;;clients leaving services, registering with new services, or preparing to leave.
;;We can just send update messages...
(defn update-clients [ctx] (update-by :client ctx))

(comment
;;Update Services [Services notify registered, non-waiting clients of availability,
;;                 clients move to services]
;;List newly-available services.
(defn update-services  [ctx]
    ;;see if we can fill our services...
 )
)

;;For newly-available services with clients waiting, allocate the next n clients
;;based on available capacity.  Newly allocated client entities will have a
;;:move-to-service;  Clients unable to allocate, will be allocted to
;;wait (with a wait-time of up to +wait-time+).
(defn allocate-services [ctx]
  (->> ctx
       (fill-services)))

(defn allocate-waits [ctx]
  (if-let [ids (seq (not-allocated ctx))]
    (->> ids
         (take (wait-capacity ctx))
         ;;try to get the unallocated entities to wait in the waiting area.
         (reduce waiting-service ctx))
    ctx))

(defn clear-waiting-lists [ctx]
  (let [wl (store/get-entity ctx :waiting-list)]
    (if (seq wl) ;;there's some lists...
      (let [available? (or (store/get-domain ctx :unoccupied) {})]
        (reduce-kv (fn [acc svc xs]
                     (let [xs (filterv available? xs)]
                       (if (seq xs)
                         (store/assoce  acc :waiting-list svc xs)
                         (store/dissoce acc :waiting-list svc))))
                        ctx wl))
             
      ctx)))
                                                     
  
;;Any entities unallocated entities are discharged, because by this point,
;;they should have been able to leave.
(defn finalize-clients [ctx]
  (if-let [drops (seq (concat (not-allocated ctx) (departures ctx)))]
    (do (debug "dropping entities!")
        (reduce (fn [acc id]
                  (-> (send!! (store/get-entity acc id)
                              :leave nil acc)
                      (store/drop-entity id)
                      (sim/drop-entity-updates id)))
                ctx
                drops))
    (do (debug "no entities to drop!")
        ctx)))

;;Note: we "could" use dataflow dependencies, ala reagent, to establish a declarative
;;way of relating dependencies in the simulation.

(defn begin-t [ctx]
  (do (debug (core/msg ">>>>>>>>>>>>>Beginning time "
                       (core/get-time ctx) "<<<<<<<<<<<<<<"))
      (core/trigger-event :begin-t :system :system
         (core/msg ">>>>>>>>>>>>>Beginning time "
                       (core/get-time ctx) "<<<<<<<<<<<<<<") nil ctx)))

(defn end-t   [ctx]
  (do (debug (core/msg ">>>>>>>>>>>>>Ending time "
                       (core/get-time ctx) "<<<<<<<<<<<<<<"))
      (->> ctx 
      (core/trigger-event :begin-t :system :system
         (core/msg ">>>>>>>>>>>>>Ending time "
                       (core/get-time ctx) "<<<<<<<<<<<<<<") nil))))

;;note: the double-arity is to conform with the simulator
;;from spork.sim.history....we can probably look into changing
;;that in the near future.
(defn step
  ([ctx]
   (->> ctx
        (begin-t)
        (process-arrivals)
        (update-clients)
        (fill-services)
        (allocate-waits)
        (clear-waiting-lists)
        (finalize-clients)
        (end-t)))
  ([t ctx] (step ctx)))

(defn seed-ctx []
  (->> (init (core/debug! emptysim) :initial-arrivals {:n 10 :t 1}
             :default-behavior client-beh)
       (begin-t)
       (end-t)))

(defn step-day
  ([seed] (history/state-stream seed
              :tmax (* 60 8) :step-function step :keep-simulating? (fn [_] true)))
  ([] (step-day (sim/advance-time (seed-ctx)))))
;;Notes // Pending:
;;Priority rules may be considered (we don't here).  It's first-come-first-serve
;;priority right now.  Do they matter in practice?  Is there an actual
;;triage process?

(comment  ;testing
  (core/debugging!
   (def isim   (init (core/debug! emptysim) :initial-arrivals {:n 10 :t 1}
                     :default-behavior client-beh))
    (def isim1  (-> isim (end-t) (begin-t))) #_(sim/advance-time isim)
    ;;arrivals happen
    (def isim1a (process-arrivals isim1))
    #_(sim/get-updates :client (sim/get-time isim1a) isim1a)
    (def res   (update-clients isim1a))
    (def fills (fill-services res))
    (def wts   (allocate-waits fills))
    (def fin   (finalize-clients wts))
    (def e1    (end-t fin)))

    (core/debugging!
     (def isim   (init (core/debug! emptysim) :initial-arrivals {:n 10 :t 1}
                       :default-behavior client-beh))
     (def t1 (step (end-t isim)))
     (def t2 (step t1))
     (def t3 (step t2))
     (def t4 (step t3))
     (def t5 (step t4))
     )

; (def asim (sim/advance-time isim1a))

  )


(comment
  
(defn discrete-entity-history
  "Given a context and an entity id to follow, returns a 
   map of the discrete values of the entity's history 
   as a function of time.  Ensures that only inflections 
   where the entity history changes are captured. 
   Caller may supply an optional sample? function  
   to determine if frames should be dropped."
  [ctx id & {:keys [sample?] :or {sample?
                                  (fn [x] true)}}]
  (let [tfinal (when (and (coll? sample?)
                          (every? number? sample?))
                 (reduce max sample?))
        sample? (if tfinal (let [time?  (set sample?)]
                             (fn [ctx]
                               (time? (:t ctx))))
                    sample?)]
  (->>  (as-stream ctx)
        ;;(raw-frames) elided for now.
        (map (fn [[t ctx :as f]]
               (let [e   (store/get-entity ctx id)]
                 (assoc e :t t))))
        (filter :name) ;;unnamed entities don't exist
        (take-while (if tfinal (fn [f] (<= (:t f) tfinal))
                        (fn [x] true)))
        (filter #(and (sample? %)
                      (== (:last-update %) (:t %))))
        )))

  )
