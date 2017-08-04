;;An implementation of a behavior tree that
;;responds to messages, ala the Actor model.
(ns spork.ai.actor
  (:require [spork.ai.core :as ai :refer
             [deref! fget fassoc  push-message- debug ->msg]]
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
            [spork.ai.behaviorcontext :as base :refer :all]
            [spork.ai [machine :as fsm]]
            [spork.sim [core :as core]]
            [spork.cljgraph.core :as graph]
            [spork.util.general     :as gen]        
            [spork.data.priorityq   :as pq]
            [clojure.core.reducers  :as r]
            [spork.entitysystem.store :as store :refer :all :exclude [default]]
            [spork.sim.simcontext :as sim]
            [clojure.core.reducers :as r]
            )
  (:import [spork.ai.behaviorcontext behaviorenv]))


;;__utils__
(def ^:constant +inf+ Long/MAX_VALUE)

(defmacro ensure-pos!
  "Ensures n is a positive, non-zero value, else throws an
   exception."
  [n]
  `(if (pos? ~n) ~n
       (throw (Exception. (str [:non-positive-value ~n])))))

(defmacro non-neg!
  "Ensures n is a positive or zero value, else throws an
   exception."
  ([lbl x]
   `(if (not (neg? ~x)) ~x
        (throw (Exception. (str [~lbl  :negative-value ~x])))))
  ([x]    `(if (not (neg? ~x)) ~x
               (throw (Exception. (str [:negative-value ~x]))))))

(defmacro try-get [m k & else]
  `(if-let [res# (get ~m ~k)]
     res# 
     ~@else))

(defn rconcat
  ([& colls]
   (reify clojure.core.protocols/CollReduce
     (coll-reduce [this f1]
       (let [c1   (first colls)
             init (reduce (fn [acc x] (reduced x)) (r/take 1 c1))
             a0   (reduce f1 init (r/drop 1 c1))]
         (if (reduced? a0) @a0
             (reduce (fn [acc coll]
                       (reduce (fn [acc x]
                                 (f1 acc x)) acc coll)) a0 (r/drop 1 colls)))))
     (coll-reduce [this f init]
       (reduce (fn [acc coll]
                (reduce (fn [acc x]
                          (f acc x)) acc coll)) init colls))
     clojure.lang.ISeq
     (seq [this] (seq (into [] (r/mapcat identity colls) )))
     )))

(defn pass 
  [msg ctx]  
  (->> (success ctx)
       (core/debug-print [:passing msg])))

(def ^:dynamic *interact* false)

(defmacro if-y [expr & else]
  `(if ~'*interact* (if (and  (= (clojure.string/upper-case (read)) "Y"))
                      ~expr 
                      ~@else)
       ~expr))

(defmacro log! [msg ctx]
  `(do (debug ~msg)
       ~ctx))

;;migrate.,..
(defn echo [msg]
  (fn [ctx] (do (debug msg) (success ctx))))

(defmacro deref!! [v]
  (let [v (with-meta v {:tag 'clojure.lang.IDeref})]
    `(.deref ~v)))

(defmacro val-at
  "Synonimous with clojure.core/get, except it uses interop to 
   directly inject the method call and avoid function invocation.
   Intended to optimize hotspots where clojure.core/get adds  
   unwanted overhead."
  [m & args]
   (let [m (with-meta m  {:tag 'clojure.lang.ILookup})]
    `(.valAt ~m ~@args)))

;;let's see if we can memoize get-next-position for big gainz yo...
(defn memo-2 [f & {:keys [xkey ykey] :or {xkey identity ykey identity}}]
  (let [xs (java.util.HashMap.)]
    (fn [x1 y1]
      (let [x (xkey x1)
            y (ykey y1)]
        (if-let [^java.util.HashMap ys (.get xs x)]
          (if-let [res (.get ys y)]
            res
            (let [res (f x1 y1)]
              (do (.put ys y res)
                  res)))
          (let [res   (f x1 y1)
                  ys    (doto (java.util.HashMap.)
                          (.put y res))
                _     (.put xs x ys)]
            res))))))

(defn entity-state [u] (-> u :statedata :curstate))

;;(defmacro kw? [x]
;;  `(instance? clojure.lang.Keyword ~x))

(defn has?
  "Dumb aux function to help with state sets/keys.
   If we have an fsm with statedata, we can check 
   whether the state representation has a desired 
   state - or 'is'  the state - even in the 
   presence of multiple conjoined states - ala 
   state sets."
  [ys xs]
  (if (set? xs) 
    (if (set? ys) (when (every? ys xs) xs)
        (and (== (count xs) 1)
             (xs ys)))
    (if (set? ys) 
        (and (== (count ys) 1)
             (ys xs))
        (when (= ys xs) ys))))

(defn has-state? [u s]
  (has? (entity-state u) s))

(defn waiting? [u]  (has-state? u :waiting))

;;entities have actions that can be taken in a state...
#_(def default-statemap
  {:reset            reset-beh
;   :global          
   :abrupt-withdraw  abrupt-withdraw-beh
   :recovery         recovery-beh
   :followon         age-unit
;   :recovered        (echo :recovered-beh)
   ;:end-cycle
;   :spawning        spawning-beh   
   :demobilizing     dwelling-beh
   "DeMobilizing"    dwelling-beh
   protocols/demobilization dwelling-beh

   :bogging           bogging-beh
   protocols/Bogging  bogging-beh
   
   ;;Added for legacy compatibility...
   :non-bogging       dwelling-beh 
   
   :recovering      (echo :recovering-beh)
   "Recovering"     (echo :recovering-beh)
   
   :dwelling          dwelling-beh
   protocols/Dwelling dwelling-beh

   ;;Need to make sure we don't add bogg if we're already bogging...
   :overlapping           bogging-beh
   protocols/Overlapping  bogging-beh

   :waiting        (echo :waiting-state) #_(->seq [(echo :waiting-state)
                                                   defer-policy-change])
   })

(def default-state-map {:waiting (echo :waiting-state)})

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

(defn handle-messages-with [handler]
  (befn handle-messages ^behaviorenv {:keys [entity current-messages ctx] :as benv}
      (when current-messages
        (reduce (fn [acc msg]                  
                  (message-handler msg (val! acc)))
                (success (assoc benv :current-messages nil))
                current-messages))))

(befn up-to-date {:keys [entity tupdate] :as benv}
      (let [e (reset! entity (assoc @entity :last-update tupdate))]
        (echo [:up-to-date (:name e) :cycletime (:cycletime e) :last-update (:last-update e) :tupdate tupdate
               :positionpolicy (:positionpolicy e)])))

(def process-messages-beh
  (->or [(->and [(echo :check-messages)
                         check-messages
                         handle-messages])
         (echo :no-messages)]))

(defn up-to-date? [e ctx] (== (:tupdate e) (:tupdate ctx)))

;;This will become an API call...
;;instead of associng, we can invoke the protocol.
(befn schedule-update  ^behaviorenv {:keys [entity ctx new-messages] :as benv}
      (let [st       (deref! entity)
            nm       (:name st)
            duration (:wait-time st)
            tnow     (:tupdate (deref! ctx))
            tfut     (+ tnow duration)
            _        (debug 4 [:entity nm :scheduled :update tfut])
            ;_ (when new-messages (println [:existing :new-messages new-messages]))
            ]        
        (success (push-message- benv nm nm (->msg nm nm tfut :update)))))

(defn ->mailbox
  "Creates a behavior node that processes messages according to the message-handler, 
   where message-handler is a function the takes a behavior environment and a message, 
   and returns a behavior environment.  Note: currently the reducer will just plow 
   through messages, that is, we don't have the concept of selective-receive.
   We just process messages in bulk."
  [message-handler]
  (->bnode :mailbox nil
     (fn [^behaviorenv benv]
       (let [{:keys [entity current-messages ctx]} benv]
         (when current-messages
           (reduce (fn [acc msg]                  
                     (message-handler msg (val! acc)))
                   (success (assoc benv :current-messages nil))
                   current-messages))))))




;;OBE for now...
;;==============

;;The global sequence of behaviors that we'll hit every update.
;;These are effectively shared behaviors across most updates.
#_(def global-state
    (->seq [(echo :aging)
            age-unit          
            (echo :aged)
            moving-beh]))

;;The root behavior for updating the entity.
#_(def update-state-beh
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

;;if we have a message, and the message indicates
;;a time delta, we should wait the amount of time
;;the delta indicates.  Waiting induces a change in the
;;remaining wait time, as well as a chang
#_(befn wait-in-state ^behaviorenv [entity current-message ctx]
  (let [;_ (println [:wait-in-state entity msg])
        msg    current-message
        t     (fget msg :t)
        delta (- t (fget (deref! entity) :t))]
    (when-let [duration (fget  (deref! entity) :wait-time)]
      (if (<= delta duration) ;time remains or is zero.
         ;(println [:entity-waited duration :remaining (- duration delta)])
        (merge!!  entity {:wait-time (- duration delta)
                          :tupdate t}) ;;update the time.
        (do ;can't wait out entire time in this state.
          (merge!! entity {:wait-time 0
                           :tupdate (- t duration)}) ;;still not up-to-date
           ;;have we handled the message?
           ;;what if time remains? this is akin to roll-over behavior.
           ;;we'll register that time is left over. We can determine what
           ;;to do in the next evaluation.  For now, we defer it.
          (bind!! {:current-message (.assoc ^clojure.lang.Associative msg :delta (- delta duration))}
                 )
          )))))



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
(defn ->handler [key->beh & {:keys [msg->key default]
                             :or {msg->key :msg
                                  default (b/always-fail}}}]
  (fn handler [benv]
    (let [entity           (.entity benv)
          current-messages (.current-messages benv)
          ctx              (.ctx benv)
          _    (ai/debug (str [(:name (deref! entity)) :handling msg]))]
      (let [b (or (key->beh (msg->key msg)) default)]
        (beval b benv)))))
          
(def echo-handler  (->handler {} :default echo))
(def default-handler
  (->handler     ;;allow the entity to change its behavior.
         {:become (push! entity :behavior (:data msg))
          :do     (->do (:data msg))
          :echo   (->do  (fn [_] (println (:data msg))))
          (do ;(println (str [:ignoring :unknown-message-type (:msg msg) :in  msg]))
            (sim/trigger-event msg @ctx) ;toss it over the fence
                                        ;(throw (Exception. (str [:unknown-message-type (:msg msg) :in  msg])))
            (success benv)
            )}))

;;type sig:: msg -> benv/Associative -> benv/Associative
;;this gets called a lot.
#_(defn message-handler [msg ^behaviorenv benv]
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
