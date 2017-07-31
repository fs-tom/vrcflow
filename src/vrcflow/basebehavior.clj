;;Definitions of general entity behavior concepts,
;;including behavior environments for evaluating
;;entity behaviors in a functional manner.
(ns vrcflow.basebehavior)

;;This is a staging ground for stuff ported from M4.
;;Looking for general patterns and refactorability.
(comment

;;the entity will see if a message has been sent
;;externally, and then compare this with its current internal
;;knowledge of messages that are happening concurrently.
(befn check-messages ^behaviorenv {:keys [entity current-messages ctx] :as benv}
   (if-let [old-msgs (fget (deref! entity) :messages)] ;we have messages
     (when-let [msgs   (pq/chunk-peek! old-msgs)]
       (let [new-msgs  (rconcat (r/map val  msgs) current-messages)
             _         (b/swap!! entity (fn [^clojure.lang.Associative m]
                                          (.assoc m :messages
                                                  (pq/chunk-pop! old-msgs msgs)
                                                 )))]
         (bind!! {:current-messages new-msgs})))
     (when current-messages
       (success benv))))

;;this is a dumb static message handler.
;;It's a simple little interpreter that
;;dispatches based on the message information.
;;Should result in something that's beval compatible.
;;we can probably override this easily enough.
;;#Optimize:  We're bottlnecking here, creating lots of
;;maps....

;;Where does this live?
;;From an OOP perspective, every actor has a mailbox and a message handler.
;;

;;so now we can handle changing state and friends.
;;we can define a response-map, ala compojure and friends.

;;type sig:: msg -> benv/Associative -> benv/Associative
;;this gets called a lot.
(defn message-handler [msg ^behaviorenv benv]
  (let [entity           (.entity benv)
        current-messages (.current-messages benv)
        ctx              (.ctx benv)]
    (do (ai/debug (str [(:name (deref! entity)) :handling msg]))
      (beval 
       (case (:msg msg)
         :move
         (let [move-info (:data msg)
               {:keys [wait-time next-location next-position deltat] :or
                {wait-time 0 deltat 0}} move-info
               _ (debug [:executing-move move-info  msg (:positionpolicy @entity)])]
           (beval (move! next-location deltat next-position wait-time) benv))
         ;;allow the entity to invoke a state-change-behavior
         ;;We can always vary this by modifying the message-handler         
         :change-state           
         ;;generic update function.  Temporally dependent.
         ;;we're already stepping the entity. Can we just invoke the change-state behavior?
         (let [state-change (:data msg)
               _            (debug [:state-change-message state-change msg])]
           (beval change-state-beh (assoc benv :state-change state-change
                                               :next-position (or (:next-position state-change)
                                                                  (:newstate state-change)))))
         :change-policy
         ;;Note: this is allowing us to change policy bypassing our wait state...
         ;;We need to put a break in here to defer policy changes.
         ;;Policy-changes are handled by updating the unit, then
         ;;executing the  change-policy behavior.
         ;;Note: we could tie in change-policy at a lower echelon....so we check for
         ;;policy changes after updates.
         (beval policy-change-state (assoc benv :policy-change (:data msg)))
         
         :update (if (== (get (deref! entity) :last-update -1) (.tupdate benv))
                   (success benv) ;entity is current
                   (->and [(echo :update)
                                        ;roll-forward-beh ;;See if we can replace this with update-state...
                           update-state-beh
                           
                           ]))
         :spawn  (->and [(echo :spawn)
                         (push! entity :state :spawning)
                         spawning-beh]
                        )
         ;;Allow the entity to apply location-based information to its movement, specifically
         ;;altering behavior due to demands.
         :location-based-move         
         (beval location-based-beh 
                (assoc benv  :location-based-info (:data msg)))
         ;;Like a location-based move, except with a simple wait time guarantee, with a
         ;;reversion to the original state upon completion of the wait.
         :wait-based-move
         (beval wait-based-beh
                (assoc benv  :wait-based-info (:data msg)))
         ;;allow the entity to change its behavior.
         :become (push! entity :behavior (:data msg))
         :do     (->do (:data msg))
         :echo   (->do  (fn [_] (println (:data msg))))
         (do ;(println (str [:ignoring :unknown-message-type (:msg msg) :in  msg]))
           (sim/trigger-event msg @ctx) ;toss it over the fence
                                        ;(throw (Exception. (str [:unknown-message-type (:msg msg) :in  msg])))
           (success benv)
           ))
       benv))))

;;we'd probably like to encapsulate this in a component that can be seen as a "mini system"
;;basically, it'd be a simple record, or a function, that exposes a message-handling
;;interface (could even be a generic fn that eats packets).  For now, we'll work
;;inside the behavior context.  Note, the entity is a form of continuation....at
;;least the message-handling portion of it is.

;;message handling is currently baked into the behavior.
;;We should parameterize it.

;;handle the current batch of messages that are pending for the
;;entity.  We currently define a default behavior.
(befn handle-messages ^behaviorenv {:keys [entity current-messages ctx] :as benv}
      (when current-messages
        (reduce (fn [acc msg]                  
                  (message-handler msg (val! acc)))
                (success (assoc benv :current-messages nil))
                current-messages)))


(def process-messages-beh
  (->or [(->and [(echo :check-messages)
                         check-messages
                         handle-messages])
                 (echo :no-messages)]))

;;The root behavior for updating the entity.
(def update-state-beh
  (->seq [(echo :<update-state-beh>)
         ; process-messages-beh
          (->or [special-state
                 (->seq [(echo :<do-current-state>)
                         do-current-state
                         (echo :global-state)
                         (fn [ctx]
                           (if-y 
                            global-state
                            (fail ctx)))])
                 up-to-date])]))

;;While we're rolling, we want to suspend message processing.
;;We can do this by, at the outer level, dissocing the messages...
;;or, associng a directive to disable message processing...

;;we want to update the unit to its current point in time.  Basically, 
;;we are folding over the behavior tree, updating along the way by 
;;modifying the context.  One of the bits of context we're modifying 
;;is the current deltat; assumably, some behaviors are predicated on 
;;having a positive deltat, others are instantaneous and thus expect 
;;deltat = 0 in the context.  Note, this is predicated on the
;;assumption that we can eventually pass time in some behavior....
(befn roll-forward-beh {:keys [entity deltat statedata] :as benv}
      (do (debug [:<<<<<<<<begin-roll-forward (:name @entity) :last-update (:last-update @entity)])
          (cond (spawning? statedata) (->seq [spawning-beh
                                              roll-forward-beh])
                                       
                (pos? deltat)                
                (loop [dt   deltat
                       benv benv]
                  (let [sd (:statedata    benv)            
                        timeleft    (fsm/remaining sd)
                        _  (debug [:sd sd])
                        _  (debug [:rolling :dt dt :remaining timeleft])
                        ]
                    (if-y 
                     (if (<= dt timeleft)
                       (do (debug [:dt<=timeleft :updating-for dt])
                           ;;this is intended to be the last update...
                           ;;as if we're send the unit an update message
                           ;;for the last amount of time...                           
                           (beval (->seq [update-state-beh
                                          process-messages-beh]) ;we  suspend message processing until we're current.
                                  (assoc benv :deltat dt)))
                       (let [residual   (max (- dt timeleft) 0)
                             res        (beval update-state-beh (assoc benv  :deltat timeleft))]
                         (if (success? res) 
                           (recur  residual ;advance time be decreasing delta
                                   (val! res))
                           res)))
                     nil)))

                :else
                (->seq [update-state-beh
                        process-messages-beh]))))


)
