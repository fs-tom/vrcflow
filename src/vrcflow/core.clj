;;A simple flow model to examine a population of entities
;;finding and receiving services to meet needs.
(ns vrcflow.core
  (:require
   [vrcflow [data :as data] [actor :as actor] [behavior :as beh] [services :as services]
    [stacked :as stacked]]
   [incanter [core :as i] [charts :as c]]
   [spork.entitysystem.store :refer [defentity keyval->component] :as store]
   [spork.util [table   :as tbl] [stats :as stats]]
   [spork.sim  [core :as core] [simcontext :as sim] [history :as history]]
   [spork.ai   [behaviorcontext :as bctx]
               [messaging :refer [send!! handle-message! ->msg]]
               [core :refer [debug]]]
   ))

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

;;Simulation Systems
;;==================
;;Since we're simulating using a stochastic process,
;;we will unfold a series of arrival times using some distribution.
;;We need something to indicate arrivals eventfully.

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


(defn init
  "Creates an initial context with a fresh distribution, schedules initial
   batch of arrivals too."
  ([ctx & {:keys [default-behavior service-network initial-arrivals]
           :or {default-behavior beh/client-beh
                service-network  services/basic-network}}]
   (let [dist        (stats/exponential-dist 5)
         f           (fn interarrival [] (long (dist)))]
     (->>  ctx
           (sim/merge-entity  {:arrival {:arrival-fn f}
                               :parameters {:default-behavior default-behavior
                                            :service-network  service-network}})
           (services/schedule-arrivals
            (or initial-arrivals
                (services/next-batch (sim/get-time ctx) f default-behavior)))
           (services/register-providers service-network)
           )))
  ([] (init emptysim)))

(defn process-departures [ctx]
  (->> (store/get-domain ctx :departure)
       (keys)
       (reduce (fn [acc id]
                 (store/drop-entity acc id))  ctx)))

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
           new-batch    (services/next-batch (:t pending) arrival-fn
                                    (store/gete ctx :parameters :default-behavior))]
       (->> ctx
            (services/handle-arrivals (:t pending) new-entities)
            (services/schedule-arrivals new-batch)))
     (do (spork.ai.core/debug "No arrivals!")
         ctx)))
  ([ctx] (process-arrivals services/batch->entities ctx)))

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

;;when we assign an entity to a service, we need to
;;eliminate him from the waiting lists; or otherwise
;;mark him as unavailable.
(defn assign-service [ctx svc ents provider-caps]
  (let [ents   (filterv (comp (partial services/service-eligible? svc)
                              #(store/get-entity ctx %))  ents)
        deltas (fill-to (count ents) provider-caps)      
        idx    (atom 0)
        assign-ent (fn [ctx provider]
                     (let [id (nth ents @idx)
                           _  (reset! idx (inc @idx))]
                           (services/allocate-provider ctx provider svc id)))]   
     (reduce (fn blah [acc [provider n remaining]]
               (reduce (fn [acc _] (assign-ent acc provider)) acc (range n)))
             ctx deltas)))
                 
;;note: some entities may no longer care about services...
;;Assume, for now, that the waiting list is up to date,
;;i.e. occupied entities are pruned out...
;;Note: at the most, we'll have 28 entities waiting...
(defn fill-services
  ([waiting ctx]
   (reduce-kv (fn [acc svc ents]
                (when-let [xs (services/available-service svc acc)] 
                  (assign-service acc svc ents xs)))
              ctx waiting))
  ([ctx]
   (fill-services (store/get-entity ctx :waiting-list) ctx)))




;;Update Clients [notify clients of the passage of time, leading to
;;clients leaving services, registering with new services, or preparing to leave.
;;We can just send update messages...
(defn update-clients [ctx] (update-by :client ctx))

;;For newly-available services with clients waiting, allocate the next n clients
;;based on available capacity.  Newly allocated client entities will have a
;;:move-to-service;  Clients unable to allocate, will be allocted to
;;wait (with a wait-time of up to +wait-time+).
(defn allocate-services [ctx]
  (->> ctx
       (fill-services)))

(defn allocate-waits [ctx]
  (if-let [ids (seq (services/not-allocated ctx))]
    (->> ids
         (take (services/wait-capacity ctx))
         ;;try to get the unallocated entities to wait in the waiting area.
         (reduce services/waiting-service ctx))
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
  (if-let [drops (seq (concat (services/not-allocated ctx)
                              (services/departures ctx)))]
    (do (debug "dropping entities!")
        (reduce (fn [acc id]
                  (-> (send!! (store/get-entity acc id)
                              :leave nil acc)                      
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
        (process-departures)
        (process-arrivals)
        (update-clients)
        (fill-services)
        (allocate-waits)
        (clear-waiting-lists)
        (finalize-clients)
        (end-t)))
  ([t ctx] (step ctx)))

;;Simulation Control and Harnessing
;;=================================
(defn seed-ctx
  [& {:keys [initial-arrivals]
      :or {initial-arrivals {:n 10 :t 1}}}]
  (->> (init (core/debug! emptysim) :initial-arrivals initial-arrivals
             :default-behavior beh/client-beh)
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

;;History Queries
;;===============

(defn provider-trends [p]
    #_(assert (<= #_util (services/utilization p) 1.0) (str ["utilization too high!" (services/utilization p) p]))
  [(:name p)
   {:utilization (core/float-trunc (services/utilization p) 2)
    :clients (count (:clients p))
    :capacity (:capacity p)}])

;;Given a history, we can get data and trends from it...
(defn frame->location-quantities [[t ctx]]
  (->> (services/providers ctx)
       (mapv provider-trends)
       (into {})
       (assoc {:t t}  :trends)))

(defn derive-location [c]
  (case (:location c)
    "exit" (if (empty? (:service-plan c)) "complete" "exit")
    
    )
  )
(defn frame->clients [[t ctx]]
  (->> (services/clients ctx)
       (mapv #(hash-map :t t :location))))


(def provider-order
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
   "Army Public Health Nursing"])
(def provider-colors
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
(defn location-quantities->chart [xs]
  (-> (->>  (for [{:keys [t trends]} xs
                  [provider {:keys [utilization]}] trends]
              {:t t :utilization utilization :provider provider})
            (i/dataset [:t :utilization :provider])
            (i/$rollup :sum  :utilization [:t :provider]))
      
      (stacked/->stacked-area-chart  :row-field :t :col-field :provider
                                     :values-field :utilization
                                     :x-label "time (minutes)"
                                     :y-label "Percent Utilization"
                                     :tickwidth 60
                                     :order-by (stacked/as-order-function  provider-order))))

(defn client-quantities->chart [xs]
  (-> (->>  (for [sample xs
                  [loc gr] (group-by :location sample)]
              {:t (:t (first gr)) :quantity (count gr) :location (or loc "empty")})
            (i/dataset [:t :quantity :location])
            (i/$rollup :sum  :quantity [:t :location]))
      
      (stacked/->stacked-area-chart  :row-field :t :col-field :location
                                     :values-field :quantity
                                     :title "Clients Located by Time"
                                     :x-label "Time (minutes)"
                                     :y-label "Quantity (clients)"
                                     :tickwidth 60
                                     :order-by (stacked/as-order-function  provider-order)
                                     :color-by provider-colors)))

;;show the distribution of provider utilization (across 1 or more replications)
(defn h->utilizations [h]
  (for [[gr xs] (group-by :provider
                          (for [pm (map :trends (concat (map frame->location-quantities h)))
                                [p m] (seq pm)]
                            (assoc (select-keys m [:utilization]) :provider p)))]
    [gr (map :utilization xs)]))

(defn hs->mean-utilizations [hs]
  (->> (for [h hs
             [gr xs] (group-by :provider
                               (for [pm (map :trends (concat (map frame->location-quantities h)))
                                     [p m] (seq pm)]
                                 (assoc (select-keys m [:utilization]) :provider p)))]
         [gr (s/mean (map :utilization xs))])
       (reduce (fn [acc [gr x]]
                 (assoc acc gr (conj (get acc gr []) x)))
               {} )))

(defn utilization-plots
  [m]
  (let [the-plot
        (let [[gr xs] (first m)]
          (doto (c/box-plot  xs
                             :legend true :x-label "" :y-label "Percent Utilization" :series-label gr :title "Utilization by Provider")))]
    (doseq [[gr xs] (rest m)]
      (c/add-box-plot the-plot xs :series-label gr))
    the-plot))

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


            
            

    (i/view (client-quantities->chart
             (map frame->clients
                  (step-day
                   (seed-ctx :initial-arrivals nil)))))
    
    
; (def asim (sim/advance-time isim1a))

  )


