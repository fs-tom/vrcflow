;;A simple flow model to examine a population of entities
;;finding and receiving services to meet needs.
(ns vrcflow.core
  (:require 
   [spork.entitysystem.store :refer [defentity keyval->component] :as store]
   [spork.util.table   :as tbl]
   [spork.sim.core     :as sim]
   [spork.ai  [machine :as fsm]]
   [spork.cljgraph.core :as graph]
   ))
;;Generic client processing algorithm.

;;Assuming a have a client entity, with a
;;pre-determined list of needs and an arrival
;;time.

(defentity client
  "Provides a constructor for building clients."
  [id & {:keys [needs arrival exit]}]
  {:components
   [:name    id
    :needs   needs 
    :arrival arrival
    :exit    exit
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

(defn service-net
  (->> (tbl/table-records svc-table)
       (map (:Label :Service))
       )
  (->> (tbl/table-records cap-table)
       (map (juxt :Name :Label :Focus :Capacity))       
       ))
