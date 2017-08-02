(ns vrcflow.data
  (:require [spork.util [table   :as tbl]]))

(def schemas
  {:services {:Name     :text
              :Label    :text
              :Services :text
              :Minutes  :long}
   :capacities {:Name     :text
                :Label    :text
                :Focus    :text
                :Capacity :long}
   :prompts    {:Category :text
                :Target :text
                :Service  :text
                :Recommended :text
                :Original :boolean}})
   

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
