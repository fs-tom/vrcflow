(ns vrcflow.data
  (:require [spork.util [table   :as tbl]
             [stats :as stats :refer [exponential-dist triangle-dist]]]
            [spork.cljgraph.core :as g]))

(defn keyword-or-lit [fld]
  (if (or (= fld "") (= (first fld) \:))
          (clojure.edn/read-string fld)
          fld))

(defn long-or-nil [fld]
  (try
    (let [n ((spork.util.parsing/parse-defaults :number) fld)]
      (long n))
    (catch Exception e Long/MIN_VALUE)))
       

(def schemas
  {:services {:Name     :text
              :Label    :text
              :Services :text
              :Minutes  :long}
   :capacities {:Name     :text
                :Label    :text
                ;:Focus    :text
                :Capacity long-or-nil}
   :prompts    {:Category :text
                :Target :text
                :Service  :text
                :Recommended :text
                :Original :boolean}
   :processes {:Name    keyword-or-lit
               :Type    :literal
               :Service :literal
               :N       :literal ;could be a fn, or a number
               :Weights :clojure
               :Target  keyword-or-lit}
   :routing {:Enabled :boolean
             :From    :text
             :To      :text
             :Weight  :number
             :Notes   :text
             }
   :parameters {:Name  :literal
                :Value :literal}})
   

;;Trend preferences and Series Coloring (could be data!)
;;======================================================
(def neo-provider-order
  ["ENTER" ;orange
   "JRPC Holding Area"  ;copper
   "Waiting"  ;dark-green
   "WAIT" ;dark-green
   "Needs Assessment" ;light-blue
   "Fast Track Processing" ;dark-blue
   "Standard Processing"  ;amber
   "Update Orders" ;yellow
   "Finance" ;blue
   "Comprehensive Processing" ;red
   "Finance (Comprehensive Counseling)" ;light-orange
   "Begin Family Services" ;olive-drab
   "DODS" ;silver
   "Casualty Assistance" ;gold
   "Tricare" ;light-red
   "Legal" ;violet
   "End Family Services" ;light-green
   "CTO" ;grey
   "Clearance" ;sea-green
   "Produce Orders" ;navy-blue
   "Shared Services & Volunteers"  ;black
   "Triage And Sort" ;black
   "HHS/State Services" ;black
   "Luggage Holding Area"  ;black
   "Move To Final Destination" ;black
   "Movement to JRPC/ERPC" ;black
   "NTS Scan Point"   ;black
   "Aircraft Arrival and Debarkation" ;black
   "Alternate NTS Scan Point" ;black
   "Customs and Immigration" ;black
   "ERC Holding Area"] ;black
  )
  
(def vrc-provider-order
  ["VRC Waiting Area"
   "VRC Reception Area"
   "Chaplain Services"
   "Army Substance Abuse Program"
   
   "Teaching Kitchen"
   "Army Wellness Center"
   "Comprehensive Soldier and Family Fitness Program"
   "Nutritional Medicine"
   "Health Promotion Operations"

   "Military And Family Readiness Center"
   "Army Public Health Nursing"

   ])

(def provider-order (into vrc-provider-order neo-provider-order))

(def vrc-provider-colors
  {"VRC Waiting Area" :dark-green
   "VRC Reception Area" :green
   "Chaplain Services"   :light-blue
   "Army Substance Abuse Program" :red
   "Teaching Kitchen"  :yellow
   "Army Wellness Center" :olive-drab
   "Comprehensive Soldier and Family Fitness Program" :maroon
   "Nutritional Medicine"  :gold
   "Health Promotion Operations" :amber
   "Military And Family Readiness Center" :dark-blue
   "Army Public Health Nursing"   :light-orange
   "completed" :sea-green
   "exited" :black
   })

(def neo-provider-colors
  {"ENTER" :orange
   "JRPC Holding Area"  :light-grey
   "Waiting"  :dark-green
   "WAIT"  :dark-green
   "Needs Assessment" :light-blue
   "Fast Track Processing" :dark-blue
   "Standard Processing"  :amber
   "Update Orders" :yellow
   "Finance" :blue
   "Comprehensive Processing" :red
   "Finance (Comprehensive Counseling)" :light-orange
   "Begin Family Services" :olive-drab
   "DODS" :silver
   "Casualty Assistance" :gold
   "Tricare" :light-red
   "Legal" :violet
   "End Family Services" :light-green
   "CTO" :grey
   "Clearance" :dark-red
   
   "Produce Orders" :purple
   "Shared Services & Volunteers"  :black
   "Triage And Sort" :black
   "HHS/State Services" :black
   "Luggage Holding Area"  :black
   "Move To Final Destination" :black
   "Movement to JRPC/ERPC" :black
   "NTS Scan Point"   :black
   "Aircraft Arrival and Debarkation" :black
   "Alternate NTS Scan Point" :black
   "Customs and Immigration" :black
   "ERC Holding Area" :black
   }
  
  )

(def provider-colors (merge vrc-provider-colors neo-provider-colors))


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
Health Promotion Operations	HPO	Leader/stakeholder support, installation-level laison 	1
VRC Reception Area	VRC	Intake/Screening	2
VRC Waiting Area	WAIT	Place for folks to wait	28
Teaching Kitchen	TK	Place to learn how to cook	20")

(def prompts
  "Category	Target	Recommended	Service	Original
Family/Social	Dealing with family member mobilizing/deploying or mobilized/deployed?		Mobilization/Deployment Support	FALSE
Family/Social	Family Budget Problems?		Financial Readiness	FALSE
Spiritual	Spiritual Counseling?	Chaplain Services	Spiritual Resiliency / Counseling	FALSE
Spiritual	About to Get Married?	Chaplain Services	Pre-Marital Workshop	FALSE
Emotional	Trouble coping?		Resilience Training (MRT and In-Processing)	FALSE
Emotional	Might be addicted?		Risk Assessment	FALSE
Emotional	Coping with addiction?		Risk Reduction & Prevention	FALSE
Emotional	Need someone to talk to?		Spiritual Resiliency / Counseling	FALSE
Personal Development	Quitting Smoking?		Tobacco Cessation	FALSE
Personal Development	Overworked?		Healthy Life Balance Program	FALSE
Personal Development	Bad grades?		Academic Enhancement	FALSE
Personal Development	Money Problems?		Financial Readiness	FALSE
Activity	Score < 210 Overall Fitness Test	Army Wellness Center	Performance Triad (P3) / Move To Health (M2H)	TRUE
Activity	Difficulty Meeting Fitness Test Requirements	Army Wellness Center	Unit/Group Health Promotion	TRUE
Activity	Athletic Performance Enhancement	Army Wellness Center	Performance Enhancement	TRUE
Activity	Energy Management	Army Wellness Center	Healthy Life Balance Program	TRUE
Sleep	Time Management	Military Family Life Consultant	Healthy Life Balance Program	TRUE
Sleep	Stress Management	Military Family Life Consultant	Military Family Life Program	TRUE
Sleep	Insomnia	Military Family Life Consultant	Health Assessment Review	TRUE
Sleep	Pain Management	Military Family Life Consultant	Health Assessment Review	TRUE
Nutrition	Weight Loss Support	Nutrition Care (RD or Tech)	Individualized Counseling	TRUE
Nutrition	Special Diet Needs	Nutrition Care (RD or Tech)	Individualized Counseling	TRUE
Nutrition	Cooking Instructions	Nutrition Care (RD or Tech)	Individual/Group Instruction	TRUE
Nutrition	Command Referral for Weight Failure	Nutrition Care (RD or Tech)	Individualized Counseling	TRUE
Intake	Self Assessment		Screening	FALSE
Intake	Where do I go?		Routing	FALSE
Intake	Where can I wait?		Waiting	FALSE")

(def svc-table (tbl/tabdelimited->table services   :schema (:services schemas)))
(def cap-table (tbl/tabdelimited->table capacities :schema (:capacities schemas)))
(def prompt-table (tbl/tabdelimited->table prompts :schema (:prompts schemas)))

;;Some sample data for processing runs.
(def proc-caps
  "Name	Label	Capacity	Notes
Aircraft Arrival and Debarkation	Aircraft Arrival and Debarkation		
Alternate NTS  Scan Point	Alternate NTS  Scan Point	2	Mil Pers
CTO	CTO		
Casualty Assistance	Casualty Assistance	2	Civ Pers
Clearance	Clearance		
Comprehensive Processing	Comprehensive Processing	1	MOS 42A
Customs and Immigration	Customs and Immigration		
DODS	DODS	2	Civ Pers
ERC Holding Area	ERC Holding Area		
Begin Family Services	Begin Family Services	2	Civ Pers
End Family Services	End Family Services		
Fast Track Processing	Fast Track Processing		
Finance (Comprehensive Counseling)	Finance (Comprehensive Counseling)	1	MOS 42A
HHS/State Services	HHS/State Services		
JRPC Holding Area	JRPC Holding Area	4	Mil Pers
Legal	Legal	2	Civ Pers
Luggage Holding Area	Luggage Holding Area	2	Mil Pers
Move To Final Destination	Move To Final Destination		
Movement to JRPC/ERPC	Movement to JRPC/ERPC	2	Mil Pers
NTS Scan Point	NTS Scan Point	2	Mil Pers
Needs Assessment	Needs Assessment	2	Mil Pers
Produce Orders	Produce Orders	1	MOS 42A
Shared Services & Volunteers	Shared Services & Volunteers	2	Civ Pers
Standard Processing	Standard Processing	2	MOS 42A
Triage And Sort	Triage And Sort	2	Mil Pers
Tricare	Tricare	2	Civ Pers
Standard Processing	Standard Processing	2	MOS 42A
Update Orders	Update Orders	2	MOS 42A
Finance	Finance	2	MOS 42A
CTO	CTO	6	CTO Reps
Waiting	Waiting		Default Wait State
")

(def proc-routing
  "Enabled	From	To	Weight	Notes
TRUE	Aircraft Arrival and Debarkation	NTS Scan Point	0	
TRUE	NTS Scan Point	Customs and Immigration	0	
TRUE	Alternate NTS  Scan Point	Movement to JRPC/ERPC	0	
TRUE	Customs and Immigration	Movement to JRPC/ERPC	0	
TRUE	Movement to JRPC/ERPC	Luggage Holding Area	0	
TRUE	Luggage Holding Area	Triage And Sort	0	
TRUE	Luggage Holding Area	Move To Final Destination	0	
TRUE	Triage And Sort	JRPC Holding Area	0	
TRUE	Triage And Sort	Shared Services & Volunteers	0	
TRUE	Triage And Sort	ERC Holding Area	0	
TRUE	Shared Services & Volunteers	JRPC Holding Area	0	
TRUE	Shared Services & Volunteers	ERC Holding Area	0	
TRUE	JRPC Holding Area	Needs Assessment	0	
TRUE	Needs Assessment	Comprehensive Processing	2	Assumes 2 minute traversal  for generic needs assessment
TRUE	Needs Assessment	Standard Processing	2	Assumes 2 minute traversal  for generic needs assessment
TRUE	Needs Assessment	Fast Track Processing	2	Assumes 2 minute traversal  for generic needs assessment
TRUE	Fast Track Processing	Clearance	5	
TRUE	Comprehensive Processing	Produce Orders	10	
TRUE	Produce Orders	Finance (Comprehensive Counseling)	10	
TRUE	Finance (Comprehensive Counseling)	Family Services	10	
TRUE	Family Services	Begin Family Services	5	Enters into another service network (assessment of needs)
TRUE	Begin Family Services	Legal	5	This is another service network
TRUE	Begin Family Services	Casualty Assistance	5	This is another service network
TRUE	Begin Family Services	Tricare	5	This is another service network
TRUE	Begin Family Services	DODS	5	This is another service network
TRUE	Legal	End Family Services	0	This is another service network
TRUE	Casualty Assistance	End Family Services	0	This is another service network
TRUE	Tricare	End Family Services	0	This is another service network
TRUE	DODS	End Family Services	0	This is another service network
TRUE	End Family Services	CTO	0	Exits service network
TRUE	CTO	Clearance	10	
FALSE	Clearance	Luggage Holding Area	0	Dropped to eliminate cycle
TRUE	ERC Holding Area	HHS/State Services	0	
TRUE	HHS/State Services	Clearance	0	
TRUE	Standard Processing	Update Orders	5	
TRUE	Update Orders	Finance	5	
TRUE	Finance	CTO	5	
TRUE	ENTER	JRPC Holding Area	0	Annoted entry node for routing
TRUE	Clearance	EXIT	0	Annoted exit node for routing
TRUE	WAIT	Waiting	0	Annoted exit node for waiting
TRUE	Waiting	EXIT	35	Annoted exit node for wait time
")

(def proc-processes
"Name	Type	Service	N	Weights	Target
:default-process	:random-children	:add-children	1		
Begin Family Services	:random-children	:add-children	random-child-count		End Family Services
Needs Assessment	:random-children	:add-children	1	{\"Comprehensive Processing\" 1,  \"Standard Processing\" 8,  \"Fast Track Processing\" 1}	"
)

(def proc-params
"Name	Value
:seed	5555
:default-wait-time	999999
:default-wait-location	\"Waiting\"
:default-needs	#{\"ENTER\"}
:default-interarrival	(exponential-dist 5)
:default-batch-size	(triangle-dist 1 10 20)
")

;;for now, we'll just have some defaults setup.
(def proc-routing-table   (tbl/tabdelimited->table proc-routing   :schema (:routing    schemas)))
(def proc-cap-table       (tbl/tabdelimited->table proc-caps      :schema (:capacities schemas)))
(def proc-processes-table (tbl/tabdelimited->table proc-processes :schema (:processes  schemas)))
(def proc-params-table    (tbl/tabdelimited->table proc-params    :schema (:parameters schemas)))

;;i'm allowing exponential and triangular distributions...
(def default-parameters   (into {} (comp (map (juxt :Name  :Value))
                                         (map (fn [[n v]]  [n (if (list? v) (eval v) v)])))
                                proc-params-table))

(defn coerce-table
  "Coerce a table to the desired schema.  This a janky work-around for excel parsing."
  [s t]
  (if-let [schema (or (and (map? s) s) (get schemas s))]
    (-> t
        (tbl/table->tabdelimited)
        (tbl/tabdelimited->table :schema schema))
    (throw (Exception. (str [:not-a-schema-or-key s])))))

(defn coerce-tables [m]
  (reduce-kv (fn [acc tname t]
               (try 
                 (assoc acc tname (if-let [s (schemas tname)]
                                    (coerce-table s t)
                                    t))
                 (catch Exception e (do (println [:error-parsing tname])
                                        (throw e)))))
             {} m))

;;Consolidated some specialized processing functions here.
;;We do some cleanup and a bit of parsing/resolving (like stats
;;functions) that's nicer to keep in one place.
(defmacro eval-in [tgt expr]
  (let [my-ns        tgt]
    `(let [original-ns#  (symbol (str ~'*ns*))]
       (try 
         (do (in-ns ~my-ns)
              (let [res# (eval ~expr)]
                (in-ns original-ns#)
                res#))
        (catch Exception e#
          (do (in-ns original-ns#)
              (throw e#)))))))

(defmacro try-eval [expr]
  `(try (eval ~expr)
        (catch Exception e#
          (eval-in ~''vrcflow.data ~expr))))

(defn records->parameters [xs]
  (into {} (comp (map (juxt :Name  :Value))
                                         (map (fn [[n v]]  [n (if (list? v) (try-eval v) v)])))
        xs))

(defn records->routing-graph [xs & {:keys [default-weight]
                                 :or {default-weight 1 #_0}}]
  (->>  (for [{:keys [From To Weight Enabled]} (tbl/as-records xs)
              :when Enabled]
          [From To (if (and Weight (pos? Weight))
                     (long Weight) default-weight)]
          )
        (g/add-arcs g/empty-graph)))

;;we use LONG/MAX_VALUE as a substitute for infinity.  Ensure
;;that unconstrained nodes have max capacity.
(defn records->capacities [xs]
  (for [{:keys [Name Label Capacity] :as r} (tbl/as-records xs)]
    (if-not (pos? Capacity)
      (assoc r :Capacity Long/MAX_VALUE) ;;close to inf
      r)))

(defn records->processes [processes]
  (for [r (tbl/as-records  processes)]
    (reduce-kv (fn [acc k v]
                 (assoc acc
                        (keyword (clojure.string/lower-case (name k))) v))
               {} r)))
