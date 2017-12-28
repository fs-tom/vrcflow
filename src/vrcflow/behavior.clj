;;A simple flow model to examine a population of entities
;;finding and receiving services to meet needs.
(ns vrcflow.behavior
  (:require
   [vrcflow [data :as data] [actor :as actor] [services :as services]]
   [spork.entitysystem.store  :as store]
   [spork.sim  [core :as core]]
   [spork.ai   [behaviorcontext :as bctx]
               ;[messaging :refer [send!! handle-message! ->msg]]
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
   ))

(set! *print-level* 5)
(set! *print-length* 100)

;;Screening/intake provides clients with a ServicePlan, initially
;;with a single Service.

;;As the client processes through the system, additional services may
;;be acquired based on unidentified needs...


;;Entity Behavior:
;;We'll compose complex behavior for the entity lifecycle by defining
;;simpler behaviors and going from there.



;;helper function, maybe migrate to behavior lib.
(defn alter-entity [m]
  (->do #(swap! (:entity %) merge m)))

;;Migrate both echo and ->trigger into other
;;behaviors...
(defn echo [msg]
  (fn [ctx] (do (debug msg) (success ctx))))

(defn ->trigger [id from to msg-form data]
  (fn [benv]
    (let [ctx @(:ctx benv)
          _   (reset! (:ctx benv)
                      (core/trigger-event id from to msg-form data ctx))]
      (success benv))))

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
 (let [needs-fn (get parameters :needs-fn services/random-needs)]
   (alter-entity {:needs (needs-fn)})))

;;Sets the entity's upper bound on waiting
(befn reset-wait-time {:keys [entity] :as benv}
      (alter-entity {:wait-time services/+default-wait-time+}))

;#_(def screenining (->and [(get-service "screening") 
(defn set-service [nm]
  (alter-entity {:active-service nm}))  

;;enter/spawn behavior
;;For VRC, we defaulted (hard coded) to "Self Assessment" as
;;a need.  To generalize, we allow the store do define
;;a default need (i.e., a place to start asking for service),
;;from there we can instigate the entry process.
(befn enter {:keys [entity ctx] :as benv}
  (when (:spawning? @entity)
    (->seq [reset-wait-time
            (echo (str [:client-arrived (:name @entity) :at (core/get-time @ctx)]))
            (->do (fn [_]
                    (swap! entity merge {:spawning? nil
                                         :needs (or (store/gete @ctx :parameters :default-needs)
                                                    #{"Self Assessment"})})))])))
                   
(defn set-active-service [svc]
  (->alter #(assoc % :active-service svc)))

(befn set-departure {:keys [entity tupdate] :as benv}
      (->seq [(echo "scheduling departure!")
              (->do (fn [_] (swap! entity assoc :departure tupdate)))]
      ))

(defn find-plan [ent]
  (when-let [ps (seq (get ent :plan-stack))]
    (loop  [x (first ps)
            xs (rest ps)]
      (if (and x (pos? (count x)))
        {:service-plan x
         :plan-stack xs}
        (when xs 
          (recur (first xs) (rest xs)))))))

;;Prepare the entity's service plan based off of its needs.
;;If it already has one, we're done.
;;Note: to update this with our process-model, we
;;now have the potential of a plan-stack.  That is,
;;if the active plan is empty, we check to see
;;if there are pending service plans via find-plan.
(befn get-service-plan {:keys [entity ctx] :as benv}
  (let [ent  @entity]
    (if-let  [plan (:service-plan ent)]
      (if (pos? (count plan))
        (echo (str (:name ent) " has a service plan"))
        (if-let [res (find-plan ent)]
          (->seq [(echo (str (:name ent) " popped a pending service plan!"))
                  ;;update the service plan and plan-stack
                  (->do (fn [_] (swap! entity merge res)))])
          (->seq [(echo (str (:name ent) " completed service plan!"))
                  set-departure])))
      (let [_    (debug (str "computing service plan for "
                             (select-keys ent [:name :needs])))
            net  (store/gete @ctx :parameters :service-network)
            plan (services/service-plan (:needs ent) net)
            _    (debug (str (:name ent) " wants to follow " plan))]
        (->do (fn [_] (swap! entity assoc :service-plan plan)))))))

;;Entities going through screening are able to compute their needs
;;and develop a service plan.
;;Note: in the process-based model, we're ignoring this and never
;;visit the screening service, which is what we originally
;;hard coded into the VRC flow model.  We instead use the
;;graph-transitions to determine our services.
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

(befn request-next-available-service {:keys [entity ctx] :as benv}
      (let [ent @entity
            plan  (:service-plan ent)
            svcs  (keys plan)]
      (when (seq plan) ;;entity has services...
        (debug [(:name ent) :requesting-services svcs])
        (->alter (fn [benv]
                   (do
                      (swap! ctx services/get-in-line (:name ent) svcs)
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
                          services/deallocate-provider
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
                                                 :location  (if (empty? (:service-plan ent))
                                                              "completed"
                                                              "exited")
                                                 :wait-time 0}))))]
         ))

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
  (actor/process-messages-beh default-handler))

