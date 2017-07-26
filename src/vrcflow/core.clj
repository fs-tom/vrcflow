;;A simple flow model to examine a population of entities
;;finding and receiving services to meet needs.
(ns vrcflow.core
  (:require 
   [spork.entitysystem.store :refer [defentity keyval->component] :as store]
   [spork.util [table   :as tbl] [stats :as stats]]
   [spork.sim [simcontext     :as sim]]
   [spork.ai           [behaviorcontext :as bctx] [messaging :refer [send!! handle-message! ->msg]]]
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


;;Service Model - Drawn From Slides
;;=================================
(def services
  "Name	Label	Services	Minutes
Army Wellness Center	AWC	Health Assessment Review	30
Army Wellness Center	AWC	Body Composition/Fitness Testing	30
Army Wellness Center	AWC	Metabolic Testing	30
Army Wellness Center	AWC	Health Coaching	30
Army Public Health Nursing	APHN	Tobacco Cessation	15
Army Public Health Nursing	APHN	Healthy Life Balance Program	30
Army Public Health Nursing	APHN	Performance Triad (P3) / Move To Health (M2H)	30
Army Public Health Nursing	APHN	Unit/Group Health Promotion	60
Chaplain Services	CHPLN SVCS	Spiritual Resiliency / Counseling	30
Chaplain Services	CHPLN SVCS	Pre-Marital Workshop	60
Military And Family Readiness Center	MFRC	Military Family Life Program	60
Military And Family Readiness Center	MFRC	Financial Readiness	30
Military And Family Readiness Center	MFRC	Mobilization/Deployment Support	30
Health Promotion Operations	HPO	Education and Coordination	30
Nutritional Medicine	NUTR SVCS	Individualized Counseling	60
Nutritional Medicine	NUTR SVCS	Unit/Group Education	60
Army Substance Abuse Program	ASAP	Risk Assessment	60
Army Substance Abuse Program	ASAP	Risk Reduction & Prevention	60
Comprehensive Soldier and Family Fitness Program	CSF2	Resilience Training (MRT and In-Processing)	60
Comprehensive Soldier and Family Fitness Program	CSF2	Performance Enhancement	60
Comprehensive Soldier and Family Fitness Program	CSF2	Academic Enhancement	60
Teaching Kitchen	TK	Individual/Group Instruction	60
VRC Reception Area	VRC	Screening	10
VRC Reception Area	VRC	Routing	2
VRC Waiting Area	WAIT	Waiting	30
Classrooms	CLS	No Idea	0")

(def capacities
  "Name	Label	Focus	Capacity
Army Substance Abuse Program	ASAP	Group and individual-level drug use and alcohol abuse prevent classes	3
Military And Family Readiness Center	MFRC	life skills, relationships, and financial readiness training/counseling	7
Comprehensive Soldier and Family Fitness Program	CSF2	leader/MRT-level resilience training and motivational counseling	2
Chaplain Services	CHPLN SVCS	Counseling, relationships, stress management	1
Army Wellness Center	AWC	Individual-level activity, nutrition, weight management, and tobacco use screening	13
Army Public Health Nursing	APHN	Group and individual-level health promotion activities, health risk assessment, and tobacco cessation services 	8
Nutritional Medicine	NUTR SVCS	Individual-level counseling and group education/classes	2
Health Promotion Operations	HPO	Leader/stakeholder support, installation-level laison 	1")

(def svc-table (tbl/tabdelimited->table services))
(def cap-table (tbl/tabdelimited->table capacities))

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

#_(defn service-net
  (->> (tbl/table-records svc-table)
       (map (:Label :Service))
       )
  (->> (tbl/table-records cap-table)
       (map (juxt :Name :Label :Focus :Capacity))       
       ))

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
(defn with-entity [m]
  (->alter #(swap! (:entity m) merge m)))

;;this is a subset of what we have in the data; but it'll work
;;for the moment.
(def basic-needs
  ["Dealing with family member mobilizing/deploying or mobilized/deployed?"
   "Family Budget Problems?"

   "Spiritual Counseling?"
   "About to Get Married?"

   "Trouble coping?"
   "Addiction?"
   "Need someone to talk to?"

   "Quitting Smoking?"
   "Overworked?"
   "Bad grades?"
   "Money Problems?"

   "Score < 210 Overall Fitness Test"
   "Difficulty Meeting Fitness Test Requirements"
   "Athletic Performance Enhancement"
   "Energy Management"
   "Time Management"
   "Stress Management"
   
   "Insomnia"
   "Pain Management"
   "Weight Loss Support"
   "Special Diet Needs"
   "Cooking Instructions"
   "Command Referral for Weight Failure"])

;;this is just a shim for generating needs.
(defn random-needs [n]
  (reduce (fn [acc x]
            (if (acc x) acc
                (let [nxt (conj acc x)]
                  (if (== (count nxt) n)
                    (reduced acc)
                    nxt))))
          #{}
          (repeatedly #(rand-nth basic-needs))))

;;Entities need to go to intake for assessment.
;;As entities self-assess, they wait in the intake.
;;Upon completing self-assessment, they meet with
;;a counselor to derive services and get routed.
(comment
  
(befn compute-needs {:keys [ctx entity] :as benv}
      ;;we have a random set of needs we can derive
      ;;it'd be nice to have some proportional
      ;;representation of patients here...
      ;;there may be some literature from state
      ;;health to inform our sampling...
      ;;naive way is to just use a uniform distro
      ;;with even odds for picking a need.
      ;;how many needs per person?
      ;;make it exponentially hard to have each additional need?
      
      )

;;If the entity has needs, derives a set of needs based on
;;services.  This is a simple projection of the needs the
;;entity presents with to the services associated with said
;;need per the input data and the service-network.
(befn compute-services {:keys [ctx entity] :as benv}
  (when-let [needs (:needs @entity)]
    (let [proposed-services (needs->services needs (:service-network @ctx))]
      (with-entity {:needs nil
                    :services proposed-services}))))

;;Sets the entity's upper bound on waiting
(befn reset-wait-time {:keys [entity] :as benv}
      (with-entity {:wait-time +default-wait-time+}))

;;find the next service we'd like to try to acquire.
;;Eventually, we'll go by priority.  For now, we'll
;;just select the next service that happens to be on our
;;chain of services...
(befn next-available-service
      {:keys [entity] :as benv}
      )

;;enter/spawn behavior
(def enter
    (->and [compute-needs reset-wait-time]))

;;find-services
;;  should we advertise all services we're interested in?
;;  or go by entity-priority?
(def find-services
  (->seq [(->if  has-needs? needs->services)
          (->if  has-services? next-available-service)]))

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

;;Simulation Systems
;;==================
;;Since we're simulating using a stochastic process,
;;we will unfold a series of arrival times using some distribution.
;;We need something to indicate arrivals evantfully.

(defn init
  "Creates an initial context with a fresh distribution."
  ([ctx]
   ;;initialize the context
   ;;we'll setup a recurring arrival process.
   (let [f (stats/exponential-dist 5)]
     (sim/merge-entity
      {:arrivals {:pending (next-batch (sim/get-time isim) (comp long f)) 
                  :arrival-fn f}}
      ctx)))
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

;;generate an update for itself the next time...
(defn next-batch
  "Given a start time t, and an interarrival time
   function f::nil->number, generates a map of 
   {:n arrival-count :t next-arrival-time} where t 
   is computed by sampling from f, such that the 
   interarrival time is non-zero.  Zero-values 
   are aggregated into the batch via incrementing
   n, accounting for concurrent arrivals (i.e. batches)."
  [t f]
  (loop [dt (f)
         acc 1]
    (if (pos? dt)
      {:n acc :t (+ dt t)} 
      (recur (f) (inc acc)))))

(defn batch->entities [{:keys [n t] :as batch}]
  (for [idx (range n)]
    (let [nm (str t "_" idx)]
      (client nm :arrival t))))

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
           new-arrivals (next-batch (:t pending) arrival-fn)
           new-entities (batch->entities pending)]
       (->> (-> ctx 
                (sim/drop-update :arrival (:t pending) :arrival)
                (store/assoce    :arrival :arrivals new-arrivals)
                  (store/add-entities new-entities))
            (sim/request-update (:t new-arrivals) :arrival :arrival)))
     (do (spork.ai.core/debug "No arrivals!")
         ctx)))
  ([ctx] (process-arrivals batch->entities ctx)))

(defn update-by
  "Aux function to update via specific update-type.  Sends a default :update 
   message."
  [update-type ctx]
  (let [t (sim/current-time ctx)]
    (->> (sim/get-updates update-type t)
         (keys)
         (reduce (fn [acc e] (send!! e :update t acc)) ctx))))

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



;;Notes // Pending:
;;Priority rules may be considered (we don't here).  It's first-come-first-serve
;;priority right now.  Do they matter in practice?  Is there an actual
;;triage process?

