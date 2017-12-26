;;A simple flow model to examine a population of entities
;;finding and receiving services to meet needs.
(ns vrcflow.core
  (:require
   [vrcflow [behavior :as beh]
            [services :as services]
            [analysis :as analysis]]
   [incanter [core :as i]
             [charts :as c]
             [stats :as s]]
   [spork.entitysystem.store :refer [defentity keyval->component] :as store]
   [spork.util #_[table   :as tbl] [stats :as stats]]
   [spork.sim  [core :as core]
               [simcontext :as sim]
               [history :as history]]
   [spork.ai   #_[behaviorcontext :as bctx]
               [messaging :refer [send!! #_handle-message! #_->msg]]
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

;;Note: the only crucial bit of information we need for this
;;service simulation to work is the service network and the
;;entity behavior.  Everything else is generic.  So our
;;big degrees of freedom are building/defining service
;;networks, and defining how entity behaviors interpret said
;;networks.
(defn init
  "Creates an initial context with a fresh distribution, schedules initial
   batch of arrivals too.  If no inital arrivals are provided,
   generates an initial arrival batch based on the supplied
   distribution."
  ([ctx & {:keys [default-behavior service-network initial-arrivals
                  interarrival batch-size]
           :or {default-behavior beh/client-beh
                service-network  services/basic-network
                interarrival (stats/exponential-dist 5) 
                batch-size 1}}]
   (let [next-arrival (fn next-arrival [] (long (interarrival)))
         next-size    (if (number? batch-size)
                        (let [n (long batch-size)] (fn next-size [] n))
                        (fn next-size    [] (long (batch-size))))
         next-batch   (fn next-batch   [t]
                          (services/next-batch t
                           next-arrival next-size default-behavior))]
     (->>  ctx 
           (sim/merge-entity  {:arrival {:arrival-fn next-arrival
                                         :batch-size next-size
                                         :next-batch next-batch}
                               :parameters {:default-behavior default-behavior
                                            :service-network  service-network}})
           (services/schedule-arrivals
            (or initial-arrivals
                (next-batch (sim/get-time ctx)))
              #_(services/next-batch (sim/get-time ctx) f default-behavior))
           (services/register-providers service-network)
           )))
  ([] (init emptysim)))

;;remove entities marked for departure this time period.
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
           {:keys [pending arrival-fn next-batch]}    arr
           new-entities (batch->entities pending)
           new-batch    (next-batch (:t pending))
           #_(services/next-batch (:t pending) arrival-fn
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

;;Generic service-filling operations.
;;===================================
;;when we assign an entity to a service, we need to
;;eliminate him from the waiting lists; or otherwise
;;mark him as unavailable.
;;Note:
;;We have a new possibility with the advanced
;;process model.  We may have transitive
;;services that are 0-wait time.  When we allocate
;;the provider for the service, we really want
;;to flow the entity through available services.
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
  
;;Any unallocated entities are discharged, because by this point,
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

;;Simulation Step Function
;;========================
;;note: the double-arity is to conform with the simulator
;;from spork.sim.history....we can probably look into changing
;;that in the near future.
;;This is pretty generic
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
;;Creates an initial context for our simulation history,
;;mainly provides the default behavior for entities to follow
;;upon creation, and an initial batch of arrivals for scheduling.
;;If no arrivals are provided, defaults to 10 at t = 1.
(defn seed-ctx
  [& {:keys [initial-arrivals]
      :or {initial-arrivals {:n 10 :t 1}}}]
  (->> (init (core/debug! emptysim) :initial-arrivals initial-arrivals
             :default-behavior beh/client-beh)
       (begin-t)
       (end-t)))

;;This is using our step-function step, to simulate a random day at
;;60 minutes * 8 hours / minute .
(defn step-day
  ([seed] (history/state-stream seed
                                :tmax (* 60 8)
                                :step-function step
                                :keep-simulating? (fn [_] true)))
  ([] (step-day (sim/advance-time (seed-ctx)))))


;;Simple Visualization Routines
(defn samples [n]
  (pmap  (fn [_] (vec (step-day
                       (seed-ctx :initial-arrivals nil)))) (range n)))


(defn client-quantities-view []
  (->> (seed-ctx :initial-arrivals nil)
       (step-day)
       (map analysis/frame->clients)
       (analysis/client-quantities->chart)
       (i/view)))

(defn mean-utilization-view [& {:keys [n] :or {n 30}}]
  (->> (samples n)
       (analysis/hs->mean-utilizations)
       (analysis/utilization-plots)
       (i/view)))

(comment  ;testing
  (core/debugging!
   (def isim   (init (core/debug! emptysim) :initial-arrivals {:n 10 :t 1}
                     :default-behavior beh/client-beh))
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
                       :default-behavior beh/client-beh))
     (def t1 (step (end-t isim)))
     (def t2 (step t1))
     (def t3 (step t2))
     (def t4 (step t3))
     (def t5 (step t4))
     ) 
)
  


