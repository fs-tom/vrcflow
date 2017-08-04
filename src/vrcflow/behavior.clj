;;A namespace for defining and composing entity behaviors.
;;We'll define core behaviors here, leveraging the
;;behavior tree approach defined by spork.ai.behavior .

;;cribbed from marathon.ces.behavior...
;;Note: this behavior presumes the presence of a statedata
;;components, as in spork.ai.machine.statedata .
;;It's also predicated off the notion of a policy,
;;where there are delineated wait-times to get between
;;positions in the policy.  It's very much tied to the
;;original notions I had for M4....

(ns vrcflow.behavior
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
            [spork.ai [machine:as fsm]]
            [spork.util.general     :as gen]        
            [spork.data.priorityq   :as pq]
            [clojure.core.reducers  :as r]
            [spork.entitysystem.store :as store :refer :all :exclude [default]]
            [spork.sim.simcontext :as sim]
            [clojure.core.reducers :as r]
            )
  (:import [spork.ai.behaviorcontext behaviorenv]))

;;Overview
;;========

;;The general idea behind how we motivate entities to do things is to
;;use composeable behaviors - as defined in spork.ai.behavior -
;;composed into behavior "trees".  These trees simplify the typical
;;state-transition model we find in finite-state machines. Where the
;;FSM has zero or more edges - or transitions - between states,
;;behavior trees focus on a small set of composition operations -
;;called internal or intermediate nodes - that define how to traverse
;;the tree.  So, rather than evaluating the next state to transition
;;to - along with the pre, executing, and post conditions for the
;;state - we walk a tree of behaviors, where nodes along the path
;;dictate consistent idiomatic ways to evaluate child nodes.

;;Besides composition, the other core concept is that behaviors may
;;return success, failure, or (in other implementations) run to
;;indicate that a behavior node has not yet finished evaluating.  This
;;implementation - focused on unit entity behaviors - takes a fairly
;;naive view and ignores the run evaluation.  Rather, we always
;;succeed or fail.

;;Evaluation in the Behavior Environment
;;=====================================

;;Unlike traditional entity "update" or "step" functions, we maintain
;;an explicit context in which the behavior is evaluated - the
;;behavior environment (marathon.ces.basebehavior).  This context
;;provides a consistent accumulation of state through which we can
;;view evaluation of the behavior tree as a reduction, with the
;;behavior environment being the accumulated result.  Thus, we
;;traverse the tree with an initial behavior environment [reified as a
;;map with useful keys referencing the simulation context/entity
;;store, the entity being processed, the simulated time of the
;;evaluation, and any additional keys useful to evaluation].  Taken as
;;a lexical environment, the keys of the behavior environment form a
;;working set of "variables" or properties that we can either query,
;;update, redefine, add to, or otherwise use to guide behavior
;;evaluation.

;;When evaluating a behavior tree, we start from the root behavior and
;;use its evaluation rules to proceed with the reduction (i.e.
;;compute a resulting behavior environment).  The reduced behavior
;;context is then - typically - processed by merging the entity
;;reference into the simulation context reference, returning the
;;simulation context.  The function that encapsulates this functional
;;form of entity behavior processing is
;;spork.ai.behaviorcontext/step-entity .

;;Behavior evaluation occurs using the spork.ai.behavior/beval
;;function, which operates similarly to eval but in the domain of
;;behavior trees.  The evaluation rules are fairly simple:

;;If the item is a vector pair that matches [:success|fail|run ctx],
;;the vector is returned as the output for beval.

;;If the item to be evaluated is a function, then it is applied to the
;;current accumulated context to determine the next behavior to beval.
;;This means that functions may return a final result ala
;;[:success|:fail|:run ctx] or they may return another behavior
;;(function or node) which will continue to be evaluated against the
;;context.

;;If the item to be evaluated is a behavior node - anything
;;implemented the spork.ai.IBehaviorTree protocol - then it is beval'd
;;with the current accumulated context (delegating to the behave
;;function of the IBehaviorTree).

;;The current implementation assumes that the highest-level of
;;evaluation - as in spork.ai.behaviorcontext/step-entity!  will
;;always be successful.  Anything else is an error (even returning
;;[:fail ...].

;;Behavior Functions
;;=================

;;Callers may define functions that operate on the behavior
;;environment directly; in some cases this is a useful - if low level
;;- approach to defining behaviors.  Arbitrary functions that map a
;;context to a [:success ...]  or a [:fail ...] may be used as
;;behaviors, and will operate correctly under beval.

;;For convenience, and to focus on behavior tree traversal as an
;;"evaluation", the spork.ai.behavior/befn macro provides a convenient
;;way to define aforementioned behavior functions with convenient
;;destructuring and behavior result packing built in.  Using the befn
;;macro - to define behavior functions - is similar to the standard
;;clojure.core/defn form, with a change the context: The function
;;arguments correspond to a map-destructing of the behavior
;;environment, and where specified by a type hint, will compile to
;;fast field-based accessors for the specific behavior environment.
;;To elide repetitive use of (success ...) and the like, and to align
;;with clojure's idiom of using nil for failure, nil results are
;;automatically converted to (failure ...) evaluations.  Otherwise,
;;behavior evaluation continues as per beval - the caller can
;;immediately return from the behavior using (success ctx) or yield
;;another behavior as a return value - which will effectively continue
;;evaluation using the new behavior.

;;Additional operations available in a behavior function include:
;;(bind!! {:a 1 :b 2}) => (success (merge benv {:a 1 :b 2}))

;;(return! ^MapEntry [:success x]) => x
;;(return! ^MapEntry [:fail x]) => (Throw (Exeption. ...))

;;Behavior Nodes
;;==============

;;Aside from encoding custom functionality with raw functions,
;;pre-existing behavior nodes provide an expressive domain specific
;;language for defining behavioral "flow control" in a composeable
;;manner.  They effectively define custom behavior functions - again
;;returning [:success|:fail|:run ctx] behind a unified protocol.  The
;;magic lies in how a behavior node executes and interprets the
;;traversal of its children.  For example, the ->or behavior
;;corresponds to a logical or of all child nodes (or clauses).  Upon
;;evaluation, ->or will reduce its children - in order - returning on
;;the first [:success ctx] it finds, else [:fail ctx].  This is
;;similar to the 'or macro in clojure.  Similarly, the ->and will
;;return at the first sign of a failed child node, else return
;;[:success ctx] as its behavior reduction.  In-order,
;;i.e. left-to-right node traversal is a common idiom (although not a
;;constraint) in behavior trees, and allows one to follow the behavior
;;"logic" in a simple, consistent manner by following the traversal.

;;These nodes provide a simple way to compose behaviors and to
;;communicate success/failure throughout the traversal.  These trees
;;may be embedded as children of like nodes, creating sophisticatd
;;behaviors with a declarative specification.  Callers are advised to
;;use the canonical behavior nodes where possible to utilize their
;;expressive power, readability, and re-use.

;;Updating Units by Sending Messages
;;==================================

;;Technically, a unit entity update is any application of
;;spork.ai.behaviorcontext/step-entity!, in which the entity, the
;;simulation context, and a behavior - either a unique behavior
;;associated with the entity's :behavior component, or a default
;;global behavior defined in
;;spork.ai.behaviorcontext/default-behavior - are munged into a
;;spork.ai.behaviorcontext/behaviorenv.

;;Thus, stepping entities requires the simulation context/entity
;;store, the entity to update, and a message to send it.  The result
;;will be a simulation context / entity store reflecting any committed
;;changes in response to how the entity "behaved" in response to the
;;message.

;;We use messages - as defined in marathon.ces.core/->msg, as an
;;entry-point to initiate behavior and provide initial bindings for
;;the behavior environemnt.  For instance, the convenience function
;;marathon.ces.core/handle-message! merely wraps step-entity!, while
;;marathon.ces.core/send!! provides a simple API for defining messages
;;to send to the entity in addition to computing the result of a send
;;/ behavior.

;;When are Messages Sent, or When do Updates Happen?
;;=======================

;;Currently, entities send themselves messages typically in reponse to
;;"organic" events such as following a rotational policy.  Once the
;;entity is initialized, it will likely request an update at a later
;;time, the span of which is predicated based on the amount of time
;;the unit is supposed to wait in a particular state according to its
;;rotational policy.  Absent any "outside" interference, this message
;;will be propogated to the entity at the scheduled time, with the
;;entity living in eventless stasis (retaining the state from its last
;;known update) until the message is delivered.  For unit entities,
;;message delivery is dispatched during invocation of
;;marathon.ces.supply/manage-supply, at which point any units
;;scheduled for updating are notified.

;;Inorganic messages occur when external forces exert unexpected
;;control over the unit entity.  These typically manifest in events
;;like filling demand, sending units home, changing policies, or any
;;number of things that are unexplained by the unit's rotational
;;policy - yet necessary for simulation.

;;How Are Messages Processed?
;;===========================

;;Messages may occur out-of-sync with the unit's current status.  That
;;is, on the timeline the unit follows, the entity is not guaranteed
;;to have been "updated" at the same time step as the new message is
;;received.

;;Consequently, we need to synchronize, or roll the unit forward in
;;time to account for any pending updates and to bring the entity into
;;a synchronized state at the time of the message.  Unit entity
;;behavior is defined to account for an elapsed time, represented by
;;deltat in the behavior environment, which allows us to accomplish
;;rolling forward.  For instance, if a unit arrives at a dwelling
;;state, and needs to wait there for 365 days until the next update,
;;with the implication that the dwelling behavior merely adds 1 unit
;;of dwell to a dwell statistic for every elapsed day, the entity will
;;have an update scheduled 365 days later - at which point the deltat
;;will indicate the need to roll forward 365 days and thus add 365
;;days to the dwell stat.

;;If an update or similar message arrives earlier than the next
;;scheduled update, such as from an inorganic message - say a
;;deployment 18 days later, then the unit must be "aged" or rolled
;;forward 18 days to account for the elapsed time.  From that
;;synchronization point, the unit may process the pending message and
;;accomplish its deployment, initiating another scheduled update.

;;Message processing always occurs after synchronizing the unit with
;;the time frame that the message was sent.  In terms of behavior
;;trees, message processing and "rolling forward" are merely behavior
;;functions that can be composed like any other.  This opens up a raft
;;of flexible options for "communicating" with entities, as well as
;;offering the possibility for either centralizing and synchronously
;;updating entity state for all entities, or using Erlang-style
;;message-passing concurrency (or other asynchronous communication and
;;state management like clojure's software transactional memory or
;;channels) to perform asychronous updates, possibly in parallel.
;;Currently, the default implementation is synchronous and
;;centralized.

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

;;an alternative idea here...
;;use a closure to do all this stuff, and reify to give us implementations
;;for the object.  We can also just us a mutable hashmap behind the
;;scene if we want to...at some point, it's probably better to have
;;the shared-nothing approach and just leave entities in their
;;own mutable cells, isolated from other state.  We can
;;still maintain persistent history.  Everything becomes a lookup though;
;;we have to find the current value of the entity at time t;
;;More to think of here..

;;New
;;Environment for evaluating entity behaviors, adapted for use with the simcontext.
;;If we provide an address, the entity is pushed there.  So, we can have nested
;;updates inside associative structures.

;;__Utility functions__
;;testing function...
(defn spawning?     [^spork.ai.machine.statedata statedata]
  (identical?  (.curstate statedata) :spawning))

;;aux functions will most likely be plentiful.  We specifically
;;have a host of helper functions for unit-specific entity behaviors.
;;Most of them deal with how the units read their policies and stuff.
;;__Aux Functions__

(comment 
;;Pulled out to address concerns in get-wait-time.
;;Computes the wait time - i.e. transfer time - between
;;frompos and topos relative to a unit's policy and statedata.
(defn immediate-wait-time
  ([unit frompos topos {:keys [deltat statedata] :as benv}]
   (let [wt     (protocols/transfer-time (:policy unit) frompos topos)
         deltat (or  deltat 0) ;allow the ctx to override us...
         ]
     (- wt (fsm/remaining statedata))))
  ([unit frompos {:keys [deltat statedata] :as benv}]
   (immediate-wait-time unit frompos
      (get-next-position (:policy unit) frompos) benv)))

;;Could be a cleaner way to unpack our data, but this is it for now...
;;need to fix this...let's see where we use it.
;;Note: this depends on policy-wait-time, which is great, but the
;;use-case is intended for a future, planned wait.  In other words,
;;this fails us when we want to compute the wait time from a current
;;policy position - ala during a policy change.
(defn get-wait-time
  ;;WARNING: we define an inconsistency here in the 4-arity version.
  ;;If we specifcy the from,to positions, the wait-time is computed using
  ;;frompos as the the starting position.  The other arities compute
  ;;using policy-wait-time, which uses the successor wait time of the
  ;;current position - i.e. how long will I have to wait in the next position.
  ;;Current usage appears correct - namely the 3-arity version, but that
  ;;could throw us off - as it did for initial policy-change implementation!
  ([unit position {:keys [deltat statedata ctx] :as benv}] ;;uses position after current...
   (policy-wait-time (:policy unit) statedata position (or deltat 0) (or (:default-recovery unit) 0)))
  ([position {:keys [entity] :as benv}] (get-wait-time @entity position benv))
  ([{:keys [wait-time] :as benv}] wait-time))
)

;;Basic API
;;=========
;;The rest of the simulation still relies on our pre-existing API, 
;;namely that we have "change-state", and "update"

;;note that change-state already exists in marathon.sim.unit/change-state, 
;;we're merely providing an interface to the unit's behavior for it.
;;Also note that change-state is only called (currently) from
;;marathon.sim.demand (for abrupt withdraws), and marathon.sim.supply
;;(for deployments).

;;might ditch these....
(declare change-state-beh update-state-beh update-state 
         roll-forward-beh
         lite-update-state-beh
         check-overlap
         check-deployable
         finish-cycle
         spawning-beh
;;         age-unit
         moving-beh
         process-messages-beh
;;re-entry behaviors
         abrupt-withdraw-beh
         re-entry-beh
         recovery-beh
;;policy change fwd declarations
         apply-policy-change 
         defer-policy-change
         policy-change-state
         try-deferred-policy-change
;; auxillary behavior definitions.         
         location-based-beh
         wait-based-beh
         )

;;API
;;===
;;These are the entry points that will be called from the outside.
;;Under the legacy implementation, they delegated to a hard coded
;;finite state machine that interpreted rotational policy to infer
;;state transitions.  The general mechanism is to augment the
;;simulation context.  We may want to define a single function
;;load-context and unload-context the clears up any augmented
;;contextual items we put in.  That, or manage the simulation
;;context separate from the behavior context.  For now, managing
;;the simcontext along with the behavior context (treating it
;;as a huge blackboard) seems like the simplest thing to do.

;;__update-entity!__
;;Similarly, we'll have update take the context last.
;;update will depend on change-state-beh, but not change-state.
;;change-state is a higher-level api for changing things.
;;Note: this is covered by step-entity!  We need to
;;include the roll-forward-beh though, to ensure we're
;;consistent.

;;we can wrap these up and just pass a generic message for the
;;behavior to interpret.
;;change-state becomes
;;load-entity
;;add-message {:to-state to-state :deltat deltat :ctx ctx}


;;Move this out to marathon.ces.unit?
;;auxillary function that helps us wrap updates to the unit.
;;added check to prevent recording traversals to save time and
;;memory.  Does not affect debugging.
(defn traverse-unit [u t from to]   
  (-> (if marathon.ces.core/*debug* (u/add-traversal u t from to)
          u) 
      (assoc :positionpolicy  to)))

;;this is kinda weak, we used to use it to determine when not to
;;perform updates via the global state, but it's probably less
;;important now...we can actually codify this structurally
;;in the behavior tree now...
;;special states just diverted the fsm update function to
;;a different path (bypassing the global state, i.e. not
;;aging/advancing).  Where we had direct method calls to
;;other state handler functions, we can now just directly
;;encode the transition in the tree...
(definline special-state? [s]
  `(#{:spawning :abrupt-withdraw :recovered :waiting #_:recovery} ~s))

(defn just-spawned?
  "Determines if the entity recently spawned, indicated by a default
   negative spawn time or a spawntime in the present."
  [{:keys [entity ctx] :as benv}]
  (identical? (:state @entity) :spawning))

;;These accessors help us ensure that we're not
;;getting stuck in invalid transitions, or spawning
;;with funky null errors.
(defn position->state [policy positionpolicy] 
  (if-let [res  (protocols/get-state policy positionpolicy)]
    res
    (throw (Exception. (str {:unknown-position positionpolicy
                             :policy (:name policy)})))))

;;We can make this processing more sophisticated...
;;Since we 
(defn position->time [policy positionpolicy]
  (if-let [res  (protocols/get-cycle-time policy
                                          positionpolicy)]
    res
    (throw (Exception. (str {:position-not-in-cycle positionpolicy
                             :policy (:name policy)})))))
;  (let [st (:spawntime @entity)]
;    (or (neg? st)
;        (==  st    (core/get-time @ctx))))
  

(defn state-expired? [{:keys [deltat statedata] :as benv}] 
  (let [r (fsm/remaining statedata)
        dt (or deltat 0)
        ]
    (<=  r dt)))
       
;;debatable utility...
;;Not sure where we're using these guys....
(defn to-position?   [to   benv]  (identical? (:next-position benv)  to))
(defn from-position? [from  benv] (identical? (:from-position benv) from))

;;Capturing change information in a structure, rather than passing it
;;around willy-nilly in the environment.  If we have a pending
;;change, there will be changeinfo.  This only applies for instantaneous
;;changes....That way, we can communicate our state updates serially
;;by adding (and removing) changeinfo.
(comment 
(defrecord changeinfo [newstate duration followingstate])
)

;;Behaviors
;;=========

;;this is a primitive action masked as a behavior.
(defn move!
  ([location deltat destination wait-time]
   (->and [(->alter (fn [benv] (merge benv {:deltat deltat
                                            :next-position destination
                                            :next-location location
                                            :wait-time wait-time})))
           moving-beh]))
  ([deltat destination wait-time]
   (->and [(->alter (fn [benv] (merge benv {:deltat deltat
                                            :next-position destination
                                            :wait-time wait-time})))
           moving-beh]))
  ([destination wait-time]
   (->and [(->alter (fn [benv] (merge benv {:next-position destination
                                            :wait-time wait-time})))
           moving-beh]))
  ([destination]
   (->and [(->alter (fn [benv] (merge benv {:next-position destination
                                            })))
           moving-beh])))
  

;;A lot of these behaviors operate on the concept of a blackboard.
;;The behavior environment, defined in spork.ai.behaviorcontext,
;;is a map of lexical bindings that we use to evaluate the consequences
;;of a unit's behavior.  Certain behaviors may place or remove things
;;from the blackboard to communicate information with other behaviors
;;"down the line".  We can couple behaviors directly using the behavior
;;tree, or allow them to be indirectly coupled using the blackboard
;;as a form of simple event communication.  Many behaviors, like
;;update-after, and roll-forward-beh, will actually "consume"
;;items in the environment, like time.  It will be common to see
;;an ephemeral, or a transactional semantics with the behaviors.

(befn +nothing-state+ [entity deltat ctx]
     (->do (fn [_] (log! (str (:name @entity) " is doing nothing for " deltat) ctx)
             )))

;;Determines if our entities are going to wait beyond the feasible
;;time horizon.  It's not that much of a stretch to consider anything longer
;;than a decent human lifetime effectively infinite...
(defn effectively-infinite? [^long x]
  (or (== x +inf+ )
      (>= x (* 365 100))))


;;note-we have a wait time in the context, under :wait-time
;;updates an entity after a specified duration, relative to the 
;;current simulation time + duration.
;;Note: Added the invariant that we cannot have negative wait-times.
;;ensure-pos! throws an exception if we encounter negative wait times.
(befn update-after  ^behaviorenv [entity wait-time tupdate ctx]
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

(require '[clojure.pprint :as pprint])
;;our idioms for defining behaviors will be to unpack 
;;vars we're expecting from the context.  typically we'll 
;;just be passing around the simulation context, perhaps 
;;with some supplementary keys.
;;Let's think about what it means to change state....
;;Are we in fact changing the root of the behavior?
;;This is where the transition from FSM to behavior tree
;;comes in....
(befn change-state-beh!  {:keys [entity ctx statedata state-change deltat] 
                          :or   {deltat 0} :as benv}
     (when state-change
       (let [_ (echo [:state-change (:name @entity)])
             {:keys [newstate duration followingstate timeinstate] :or {timeinstate 0}} state-change
             _ (when (not duration) (throw (Exception.  (str "nil value for duration in state change behavior!"))))
             followingstate (or followingstate newstate)
             ;;we change statedata here...
             wt   (- duration timeinstate)
             _  (when (neg? wt) (throw (Exception. (str [:negative-wait-time])))) 
             _  (debug [:changing-state state-change :wait-time wt])
             newdata (assoc (fsm/change-statedata statedata newstate duration followingstate marathon.ces.core/*debug*)
                            :timeinstate timeinstate)
             benv    (merge (dissoc benv :state-change) {:statedata newdata
                                                         :duration duration
                                                         :timeinstate timeinstate
                                                         :wait-time wt})
             _       (reset! ctx (supply/log-state! (:tupdate benv) @entity (:state @entity) newstate @ctx)) 
             _       (swap! entity #(assoc % :state newstate :statedata newdata)) ;;update the entity state, currently redundant.
             ;_       (debug [:statedata statedata :newdata newdata :newstate newstate])
             ]
         (beval update-state-beh benv))))

(def change-state-beh (->seq [(echo :<change-state-beh>)
                              change-state-beh!]))
;;Aux function to compute our state change during spawn.
;;Setting up initial conditions is  a PITA, particularly
;;since it's possible that some of the input data is
;;intentionally empty or zeroed out.  This helps set up
;;the bread-n-butter wait time as a function of the
;;spawning information, if any, the entity's policy, and
;;the proposed position for the entity.
#_(defn compute-state-stats [entity cycletime policy positionpolicy]
  (let [duration (:duration (:spawn-info @entity)) ;;duration may be 0.
        ;;if so, we check policy to see if we should be waiting more than 0.
        derive? (or (not duration)
                    (zero? duration))
        duration (if derive? 
                   (let [pw (policy-wait-time policy positionpolicy)
                         _  (debug [:derived-duration (:name @entity) positionpolicy pw])]
                     pw) ;derive from policy.
                   duration)
        ;;If the position is not in the policy, then we need to
        ;;find a way to compute the duration.
        ;;If we have spawn-info, then we have duration...
        position-time (if derive? #_(pos? duration) ;prescribed.
                          (try 
                            (position->time  policy positionpolicy)
                            (catch Exception e
                              (if (protocols/leads-to-start? policy positionpolicy)
                                0
                                (throw (Exception.
                                        (str [positionpolicy :isolated-from-cycle])))))) 
                          0)        
        ;;We're running into problems here....the positionpolicy 
        cycletime     (if (< cycletime position-time)
                        position-time
                        cycletime)]
;;timeinstate also subject to spawn-info....
    {:cycletime cycletime
     :position-time position-time
     :timeinstate
     (if duration
       0
       (non-neg! "timeinstate" (- cycletime position-time)))
;;timeremaining is subject to spawn info.
     :timeremaining
     (or duration ;this should keep us from bombing out...
         (protocols/transfer-time policy
           positionpolicy 
           (protocols/next-position policy positionpolicy)))}))


(defn compute-state-stats [entity cycletime policy positionpolicy]
  (let [duration (:duration (:spawn-info @entity)) ;;duration may be 0.
        ;;if so, we check policy to see if we should be waiting more than 0.
        derive? (or (not duration)
                    (zero? duration))
        ;;if so, we check policy to see if we should be waiting more than 0.
        duration (if #_derive? (and duration (zero? duration)) 
                   (let [pw (policy-wait-time policy positionpolicy)
                         _  (debug [:derived-duration (:name @entity) positionpolicy pw])]
                     pw) ;derive from policy.
                   duration)
        
        ;;If the position is not in the policy, then we need to
        ;;find a way to compute the duration.
        ;;If we have spawn-info, then we have duration...
        position-time (if derive? ;prescribed.
                          (try 
                            (position->time  policy positionpolicy)
                            (catch Exception e
                              (if (protocols/leads-to-start? policy positionpolicy)
                                0
                                (throw (Exception.
                                        (str [positionpolicy :isolated-from-cycle])))))) 
                          0)

        ;;We're running into problems here....the positionpolicy 
        cycletime     (if (< cycletime position-time)
                        position-time
                        cycletime)
        ]
;;timeinstate also subject to spawn-info....
    {:cycletime cycletime
     :position-time position-time
     :timeinstate
     (if duration
       0
       (non-neg! "timeinstate" (- cycletime position-time)))
;;timeremaining is subject to spawn info.
     :timeremaining
     (or duration ;this should keep us from bombing out...
         (protocols/transfer-time policy
           positionpolicy 
           (protocols/next-position policy positionpolicy)))}))

;;Our default spawning behavior is to use cycle to indicate.
;;There will be times we alter the methods in which a unit 
;;spawns (initializes itself in the simulation).  It'd be nice
;;to break this out at some point, for now, we just let it go.

;;we can break the spawning behavior up into smaller tasks...
;;Find out where we're supposed to be. Do we have initial conditions? 
;;Initial conditions are currently derived from cycletime and policy. 
;;For instance, we narrowly assume that every unit exists somewhere in 
;;on a cycle at t=0, rather than setting them in arbitray deployments
;;to begin with.  This is limiting, we should be able to define
;;all kinds of initial conditions to perform upon spawn (like set
;;location, cycletime, etc.)  For now, we just replicate the
;;cycletime-derived automated initial conditions logic.

;;Given a cycletime, where should we be according to policy?
;;Behavior to control how a unit acts when it spawns.
;;We're trying to account for the unit's initial state...
;;We move from spawning to the initial location.
;;We account for having been at the initial location for 
;;timeinstate days (currently tied to cycletime - timetoposition).
;;So, what we really want to do is update the unit initially, possibly 
;;with a negative time, and advance it forward to time 0 via the
;;deltat being the timeinstate.
(befn spawning-beh ^behaviorenv {:keys [to-position cycletime tupdate statedata entity ctx]
                                 :as  benv}
      (when (spawning? statedata)     
        (let [ent @entity
              ;;we're now tracking default recovery in our context.
              {:keys [positionpolicy policy]} ent
              {:keys [curstate prevstate nextstate timeinstate 
                      timeinstateprior duration durationprior 
                      statestart statehistory]} statedata
              cycletime (or cycletime (:cycletime ent) 0)
              topos     (if (not (or to-position positionpolicy))
                            (protocols/get-position (u/get-policy ent) cycletime)
                            positionpolicy)
              nextstate (position->state policy positionpolicy)
              {:keys [timeinstate
                      timeremaining
                      cycletime
                      position-time] :as stats}
              (compute-state-stats entity cycletime policy positionpolicy)
                _ (debug [:unit (:name ent) stats])
              spawned-unit  (-> ent
                                (assoc  :cycletime cycletime
                                        :default-recovery (core/default-recovery @ctx))
                                (u/initCycles tupdate)
                                (u/add-dwell  cycletime)
                                (assoc  :last-update tupdate)
                                (dissoc :spawn-info) ;eliminate spawning data.
                                ) ;;may not want to do this..
              _             (reset! entity spawned-unit)
              state-change  {:newstate       nextstate
                             :duration       timeremaining
                             :followingstate nil
                             :timeinstate timeinstate
                             }
              _             (debug [:nextstate nextstate :state-change state-change :current-state (:state ent)])          
              ]
          (->>  (assoc benv :state-change state-change
                       :location-change {:from-location "Spawning"
                                         :to-location   (or (:location (:spawn-info ent))
                                                            topos)}
                       :next-position   topos ;queue up a move...                 
                       )
                (log!  (core/msg "Spawning unit " (select-keys (u/summary spawned-unit)
                                                               [:name :positionstate :positionpolicy :cycletime])))
                (beval (->seq [(echo :change-state)
                               change-state-beh
                               #_(fn [benv]
                                 (do (reset! ctx 
                                             (supply/log-move! tupdate :spawning (:positionpolicy @entity) @entity @ctx))
                                     (success benv)))]
                              ))))))

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

;;So, at the high level, we have a simple behavior that checks to see
;;if it can move, finds where to move to, starts the process of
;;moving (maybe instantaneous), and waits...

;;We should consider move if our time in state has expired, or 
;;if we have a next-location planned.
(befn should-move? ^behaviorenv {:keys [next-position statedata] :as benv}
      (do (debug [:should?
                  {:next-position next-position
                   :remaining     (fsm/remaining statedata)
                   :spawning?     (spawning? statedata)
                   :wait-time     (:wait-time benv)}])
          (when (or next-position
                    (zero? (fsm/remaining statedata)) ;;time is up...
                    (spawning? statedata))
            (success benv))))

(def locstates #{"Dwelling"  "DeMobilizing" "Recovering"
                 :dwelling :demobilizing :recovering :recovery})

(defn some-member [s1 s2]  
  (let [[l r]  (if (< (count s1) (count s2)) [s1 s2]
                   [s2 s1])]
    (reduce (fn [acc x]
              (if (r x)
                (reduced x)
                acc)) nil l)))

(defn position=location? [newstate]
  (if (not (set? newstate))
    (locstates newstate)
    (some-member newstate locstates)
    ))
    
;;after updating the unit bound to :entity in our context, 
;;we commit it into the supplystore.  This is probably 
;;slow....we may want to define a mutable version, 
;;or detect if mutation is allowed for a faster update
;;path.  For instance, on first encountering the unit,
;;we establish a mutable cell to its location and use that 
;;during the update process.

;;Given that we have the context for a move in place, 
;;we want to move as directed by the context.  If there 
;;is a wait time associated with the place we're moving 
;;to, we will add the wait-time to the context.  That way,
;;downstream behaviors can pick up on the wait-time, and 
;;apply it.
(befn move->statechange ^behaviorenv {:keys [entity next-position location-change
                                             tupdate statedata state-change  ctx] :as benv}
    (when-let [nextpos next-position] ;we must have a position computed, else we fail.                                       
      (let [t        tupdate
            u        @entity 
            frompos  (get     u      :positionpolicy) ;;look up where we're coming from.
            wt       (or (:wait-time benv) (get-wait-time  u  nextpos benv))  ;;how long will we be waiting?
            location-based? (:location-behavior u)
            ]
        (if (= frompos nextpos)  ;;if we're already there...
          (do (debug [:no-movement frompos nextpos {:wt wt :state-change state-change}])
              (success 
               (if state-change
                 (assoc benv :wait-time nil :next-position nil)
                 (dissoc benv :next-position)))) ;do nothing, no move has taken place.  No change in position.
          (let [_            (debug [:moving frompos nextpos])
                newstate     (or (get-state u nextpos)
                                 nextpos) ;;need to account for prescribed moves.
                newstate     (if location-based?
                               (into (->  (-> statedata :curstate)
                                          #_(disj nextpos))
                                      newstate)
                               newstate)
                _ (when (nil? newstate) (throw (Exception. (str [:undefined-transition newstate u frompos nextpos wt]))))
                state-change  {:newstate       newstate
                               :duration       wt
                               :followingstate nil
                               :timeinstate 0
                               }
                _            (reset! entity  (-> (if location-based? (dissoc u :location-behavior) u)
                                                 (traverse-unit  t frompos nextpos)
                                                 )) ;update the entity atom              
                ;;if we already have a location change set, then we should respect it.
                from-loc     (:locationname u)
                to-loc       (if-let [newloc  (:next-location benv)]
                               (do (debug [:preset-location newloc :From from-loc])
                                   newloc)
                               (if (position=location? newstate)                                       
                                 nextpos
                                 from-loc))
                ;_            (println [from-loc to-loc])
                ]
            (bind!!  ;update the context with information derived
                                        ;from moving
             {:position-change {:from-position frompos ;record information
                                :to-position   nextpos}
              :state-change    state-change
              :location-change (or location-change
                                   (when (not (identical? from-loc to-loc))
                                     {:from-location  from-loc
                                      :to-location    to-loc}))
              :wait-time     nil
              :next-position nil
              :next-location nil}
             ))
          ))))

(def movekeys #{:position-change
                :state-change
                :location-change})

(befn prescribed-move->statechange {:keys [prescribed-move tupdate] :as benv}
      (when prescribed-move
          (success (reduce-kv (fn [acc k v]
                               (if v (assoc acc k v) acc))
                             (assoc benv :prescribed-move nil) prescribed-move)
        )))


(defn prescribed? [e tupdate]
  (when-let [pm (val-at @e :prescribed-move)]
    (== (val-at pm :t) tupdate)))

;;PERFORMANCE NOTE: <HOTSPOT> - eliding debug info here saves time...
;;This hooks us up with a next-position and a wait-time
;;going forward.  We also now allow prescribed moves to
;;be set, for things like location-specific policies..
(befn find-move ^behaviorenv {:keys [entity next-position wait-time tupdate] :as benv}
      (if  (prescribed? entity tupdate)
        ;;we have a move set up..
        (let [pm (:prescribed-move @entity)
              _ (debug [:found-prescribed-move pm])
              ]
               (do (swap! entity dissoc :prescribed-move)
                   (bind!! {:prescribed-move pm})))
        ;;let's derive a move...
        (let [e  @entity                
              currentpos (:positionpolicy e)
              ;_  (when (= currentpos :re-entry)  (println (:tupdate benv)))
              p  (or next-position
                     (do (debug [:computing-position currentpos]) ;;performance 1
                         (get-next-position (:policy e)  currentpos)))                   
              wt (if (and next-position wait-time) wait-time
                     (do (debug [:computing-wait (:positionpolicy e)]) ;;performance 2
                         ;;WARNING: This may be using the following wait time...is that what we mean?
                         ;;Given the current position, it's determining how long to wait in the next position.
                         ;;I think we're good...should rename get-wait-time to something more appropriate.
                         ;;get-next-wait-time?
                         (get-wait-time @entity (:positionpolicy e) benv)))
              _ (debug [:found-move {:current-position currentpos :next-position p :wait-time wt}])
              ]
          (bind!! {:next-position  p
                   :wait-time      wt
                   }  ;;have a move scheduled...
                  ))))

;;We know how to wait.  If there is an established wait-time, we
;;request an update after the time has elapsed using update-after.
(befn wait ^behaviorenv {:keys [wait-time] :as benv}          
      (when-let [wt wait-time] ;;if we have an established wait time...
        (do  #_(debug [:sdb (:statedata benv)
                       :sde (:statedata @(:entity benv))])
            (if (zero? wt)
            ;;skip the wait, instantaneous.  No need to request an
            ;;update.
              (do (debug [:instantly-updating])
                  update-state-beh) 
              (do (debug [:waiting  wt])
                  (update-after  benv))))))

;;Note: start-cycle looks somewhat weak.  Can we fold this into
;;another behavior?

;;Units starting cycles will go through a series of procedures.
;;Possibly log this as an event?
(befn start-cycle {:keys [entity deltat tupdate] :as benv}
      (do  (swap! entity #(merge % {:cycletime     0
                                    :date-to-reset tupdate}))
           (success benv)))

;;legacy implemenation.  no longer using policystack.
;; (befn start-cycle {:keys [entity deltat tupdate] :as benv}
;;    (let [unit   @entity
;;          pstack (:policystack unit)]
;;      (do  (swap! entity #(merge % {:cycletime     0
;;                                    :date-to-reset tupdate}))
;;           (if (pos? (count pstack))
;;               (bind!! {:policy-change {:next-policy (first pstack)}})
;;               (success benv)))))

;;We may not care about cycles....
;;Should be able to specify this in our collections logic, go faster...


;;Units ending cycles will record their last cycle locally.  We broadcast
;;the change...Maybe we should just queue this as a message instead..
(befn end-cycle {:keys [entity ctx tupdate] :as benv}
  (let [cyc (assoc (:currentcycle @entity) :tfinal tupdate)
        _  (swap! entity (fn [unit]
                           (->  unit
                                (assoc :currentcycle cyc)
                                (u/recordcycle tupdate))))
        ;;notify interested parties of the event...
        _  (swap! ctx (fn [ctx]
              (sim/trigger-event :CycleCompleted
                                 (:name @entity)
                                 :SupplyStore
                                 (str (:name @entity) " Completed A Cycle")
                                 nil ctx)))]
    (success benv)))

;;dunno, just making this up at the moment until I can find a
;;definition of new-cycle.  This might change since we have local
;;demand effects that can cause units to stop cycling.
;;Wow...just got burned on this..strings are no good for identity
;;checks....since some are interned and some ore instances.  wow....
(defn new-cycle? [unit frompos topos]
  (and (not= frompos :recovered)  ;;additional criteria to cover nonbog reentry.
       (= (protocols/start-state (:policy unit)) topos)))

;;We check to see if there was a position change, and if so, if that
;;change caused us to finish a policy cycle.  Note: this only applies
;;in cyclical policies.
;;Note: We want to preclude finishing cycles if we are applying a
;;policy change.  We handle that in another state.  This keeps us
;;from entering into a policy change that sends us to reset, and
;;automagically terminates the current cycle stats.  Consistent with M3.
(befn finish-cycle ^behaviorenv {:keys [entity position-change changed-policy policy-change] :as benv}      
  (when position-change
    (let [{:keys [from-position to-position]} position-change
          no-spawn? (not (just-spawned? benv))
          new-cyc?  (new-cycle? @entity from-position to-position)
          ;_ (println [:check-cycle no-spawn? new-cyc? (not policy-change) (:tupdate benv)])
          ]
      (when (and no-spawn?
                 new-cyc?
                 (not changed-policy)) ;;If we changed-policy already, preclude...
        (do (debug [:finishing-cycle (:name @entity) from-position])
            (->seq [start-cycle
                    end-cycle
                    try-deferred-policy-change]))))))

;;Now that we have prescribed moves, the entities are going into
;;an overlapping state, but it's a state set..

(defn overlapping? [x]
  (or (identical? x protocols/Overlapping)
      (identical? x :overlapping)))

;;this is really a behavior, modified from the old state.  called from overlapping_state.
;;used to be called check-overlap.
(befn disengage {:keys [entity ctx overlapping-position tupdate] :as benv}
      (when-let [opvec overlapping-position]
        (let [[lname op] opvec 
              _    (debug [:overlapping-prescribed op])
              _    (debug [:disengaging (:name @entity) (:locationname @entity)])
              res  (identical? op :to)
              _    (swap! ctx ;;update the context...
                     #(d/disengage (core/get-demandstore %)
                          (assoc @entity :last-update tupdate) lname % res #_true))]
             (success (assoc benv :overlapping-position nil)))))
        ;; (when  overlap-detected
        ;;     (when (not (identical? res :none)) ;ugh?
        ;;       (do (debug [:disengaging (:name @entity) (:locationname @entity)])
        ;;           (swap! ctx ;;update the context...
        ;;                  #(d/disengage (core/get-demandstore %)
        ;;                                @entity (:locationname @entity) % res))
        ;;           (success benv)))))))

;;used to be called check-overlap; 
(def  check-overlap disengage)
;;Performance: We have a mild hotspot when we actually have a mild hotspot
;;when we eagerly update deployability via supply/update-deploy-status.
;;Might be possible to update deployability lazily, save a little bit.
;;We're typically "not" deploying...
(befn check-deployable ^behaviorenv {:keys [entity position-change ctx] :as benv}
   (when position-change
     (let [{:keys [from-position to-position]} position-change
           u @entity
           p (:policy u)
           _ (debug [:checking-deployable  :from from-position :to to-position])]
       (when   (or (not= (protocols/deployable-at? p from-position)
                         (protocols/deployable-at? p to-position))
                   #_(unit/can-non-bog? u))
         (do (debug [:deployable-changed! from-position to-position])
             (swap! ctx #(supply/update-deploy-status u nil nil %))
             (success benv))))))

(befn mark-overlap {:keys [entity position-change] :as benv}
      (when-let [change position-change]
        (let [{:keys [to-position from-position]}   position-change
              ;;overlapping is not triggering because we only hae on definition of
              ;;overlapping per the keyword.  There's a string version that shows
              ;;up.
              res       (cond (overlapping? to-position)   :to ;true
                              (overlapping? from-position)  :from  ;false                          
                              :else :none)
              ]
          (when (not (identical? res :none))
            (do (debug [:marking-overlap res])
                (success (assoc benv :overlapping-position [(:locationname @entity) res])))))))
            
;;When there's a change in position, we want to do all these things.
(befn change-position [entity position-change tupdate ctx]
      (when position-change
        (let [{:keys [from-position to-position]} position-change]
        (do (debug [:changed-position from-position to-position])
            (reset! ctx (supply/log-position! tupdate
                                              from-position 
                                              to-position  @entity @ctx)) ;ugly, fire off a move event.check-overlap
            (reset! entity (assoc @entity :positionpolicy to-position))
            (->seq [check-deployable                 
                    finish-cycle
                    mark-overlap
                    (->alter  #(assoc % :position-change nil
                                      :next-position nil))])))))
      
;;Performance: Mild hotspot.  Dissocing costs us here.  Change to assoc and
;;check.
;;if there's a location change queued, we see it in the env.
(befn change-location {:keys [entity location-change tupdate ctx] :as benv}
   (when location-change
     (let [;#_{:keys [from-location to-location]} #_location-change ;minor improvement..
           from-location (val-at location-change :from-location) ;;OMG, typo on location...was loction!!!
           to-location   (val-at location-change :to-location)
           _ (debug [:location-change location-change])
           _  (reset! entity (u/push-location @entity to-location))
           _  (reset! ctx    (supply/log-move! tupdate from-location to-location @entity nil @ctx))
           ] 
         ;;we need to trigger a location change on the unit...
       (success (assoc benv :location-change nil)))))

;;this is a weak predicate..but it should work for now.
(defn demand? [e] (not (nil? (:source-first e))))

;;we can do this like a scalpel..
;;All that matters is that the demand fill changes.
;;We ensure we remove the unit from the demand's
;;assignment, and then remove the unit from the demand,
;;and update the fill status of the demand.
;;If we leave a demand, we need to update its information
;;and change fill status.
;;is the movement causing a change in fill?
(befn change-fill {:keys [entity location-change ctx] :as benv}
      (when location-change
        (let [{:keys [from-location]} location-change]
          (when (demand? (store/get-entity @ctx from-location))
            (swap! ctx ;;update the context...
                   #(d/remove-unit-from-demand (core/get-demandstore %)
                                 @entity from-location %))
            (success benv)))))
        
;;with a wait-time and a next-position secured,
;;we can now move.  Movement may compute a statechange
;;in the process.
(def execute-move
  (->seq [(echo :<move->statechange>)
          (->or [prescribed-move->statechange
                 move->statechange])
          (echo :<change-position>)
          change-position
          (echo :<change-fill>)
          change-fill ;;newly added...
          (echo :<change-location>)
          change-location
          change-state-beh
          (echo :<check-overlap>) ;moved before change-position
          check-overlap ;;Added, I think I missed this earlier...

          (echo :waiting)
          wait
          ]))


;;Movement is pretty straightforward: find a place to go, determine 
;;any changes necessary to "get" there, apply the changes, wait 
;;at the location until a specified time.
(def moving-beh 
  (->and [(echo :moving-beh)
          should-move? ;if there is a next position or our time in state expired.
          find-move    ;determine the wait-time, and possibly the next-position to wait at.         
          (echo :execute-move)
          execute-move
          ]))

;;PERFORMANCE NOTE: Minor HotSpot
;;Changed to == instead of zero? due to minor perf issues.

;;State handler for generic updates that occur regardless of the state.
;;These are specific to the unit data structure, not any particular state.
;;Should we keep a timestamp with the unit? That way we can keep track
;;of how fresh it is.
(befn age-unit ^behaviorenv {:keys [deltat statedata entity ctx] :as benv}
      (let [^long dt (or deltat 0)]
        (if (== dt 0)
            (success benv) ;done aging.
            (let [e  @entity
                  ;_ (println [:currentcycle (:currentcycle e)])
                  _  (when-not (u/waiting? e)
                       (swap! entity #(u/add-duration  % dt)))
                       #_(debug [:skipping :add-duration (:name entity)]) ;;update the entity atom
                  _  (debug [:aging-unit deltat
                             :cycletime (:cycletime @entity)]) 
                  ]
              (bind!! {:deltat 0 ;is this the sole consumer of time?
                       :last-update (unchecked-inc deltat)
                       :statedata   (fsm/add-duration statedata dt)})))))
 
;;Dwelling just increments statistics..
(befn dwelling-beh ^behaviorenv {:keys [entity deltat] :as benv}
      (when (pos? deltat)
        (do (debug [:dwelling deltat])
            (swap! entity  #(u/add-dwell % deltat))
            (success benv))))

;;Bogging just increments stastistics..
(befn bogging-beh ^behaviorenv {:keys [entity deltat] :as benv}
      (when (pos? deltat)
        (do (debug [:bogging deltat])
            (swap! entity  #(u/add-bog % deltat))
            (success benv))))

;;This is a little weak; we're loosely hard coding
;;these behaviors.  It's not terrible though.
(befn special-state {:keys [entity statedata] :as benv}
      (case (:state @entity)
        :spawning spawning-beh
        :abrupt-withdraw (do (debug [:<special-state-abw>])
                             abrupt-withdraw-beh)
        :recovery      recovery-beh ;moving-beh ;;setup the move to recovered.
        :recovered     (->and [(echo :recovered-beh)
                               (->seq [re-entry-beh
                                       ;;TODO: Optimize. We can skip the re-entry,
                                       ;;go to policy-change directly.
                                       (->if (fn [{:keys [entity]}]
                                               (zero? (u/get-bog @entity)))
                                             try-deferred-policy-change)])
                               
                               ;reset-beh
                               ])
        ;:waiting       (success benv) ;up-to-date
        (fail benv)))

;;rest-beh is kind of what we want to do.  We'd like to
;;compute the unit's now position in its old policy.
;;What about pending policy changes? [how'd marathon handle them in vba?]
;;I think we deferred until reset actually.

;;Follow-on state is an absorbing state, where the unit waits until a changestate sends it elsewhere.
;;The only feasible state transfers are to a reentry state, where the unit re-enters the arforgen pool
;;in a dynamically determined position, or the unit goes to another demand compatible with the
;;followon code.
(befn followon-beh {:keys [entity ctx] :as benv}
      (let [fc (u/followon-code @entity)
            _  (debug [:trying-followon (:name @entity) fc])]
        (when fc ;if the unit has a followon code
          (do ;register the unit as a possible followOn
                                        ;(println [(:name @entity) :added-followon :for [fc]])
            ;;Note: we have a problem here, since add-followon ends up getting our entity
            ;;out-of-sync with the entity reference stored in the context...
            ;;We add a bunch of components to the entity, like :followon, which may
            ;;end up getting ditched when we merge the entity atom in at the end
            ;;of the transaction...
            (swap! ctx #(supply/add-followon (core/get-supplystore %) @entity %))
            (reset! entity (-> (store/get-entity @ctx (:name @entity))
                               (merge {:state :followon})))
                                        ;age-unit
            (debug [:waiting-in-followon-status fc])
            (->seq [(->alter (fn [b]
                               (merge b {:wait-time +inf+
                                          :next-position :followon ;(:positionpolicy @entity) ;:followon
                                          :next-state  :followon;:abruptwithdraw
                                            })))
                    moving-beh])
                                        ;?
            ))))
      
;;way to get the unit back to reset.  We set up a move to the policy's start state,
;;and rip off the followon code.
(befn reset-beh {:keys [entity] :as benv}
      (let [pos             (protocols/start-state (:policy @entity))
            wt              (immediate-wait-time @entity pos benv)
            _   (debug [:immediate-reset :from (:positionpolicy @entity) :to pos :wait-time wt])
            _               (swap! entity #(assoc % :followoncode nil))
            ]
        (beval moving-beh (assoc benv :next-position pos
                                      :wait-time wt))))

;; 'A state to handle reentry into the available pool....
(def invalid?  #{"Deployed" "Overlapping"})

;;Note:
;;Attempting to match m3 behavior exactly.  Units re-entering
;;with 0 bog and a pending policy change should go ahead
;;and change policies vs. going through re-entry in the
;;current cycle's policy.

;;Kind of like reset, except it's not guaranteed we go to reset.
(befn re-entry-beh {:keys [entity ctx tupdate] :as benv}
      (let [unit   @entity
            p           (:policy    unit)
            current-pos (:positionpolicy unit)
            ct          (:cycletime unit)
            _      (when (< ct 0) (throw (Exception. (str "Cycle Time should not be negative!"))))
            _      (when (invalid? current-pos)
                     (throw (Exception.  "Cannot handle during deployment or overlap")))
            is-deployable  (protocols/deployable-by? p ct)
            positionA  current-pos
            positionB (protocols/get-position p ct)
            _         (when (invalid? positionB)
                        (throw (Exception.  (str "Cannot handle during deployment or overlap: " positionB))))
            timeremaining  (protocols/transfer-time p positionB (protocols/next-position p positionB))
            timeinstate    (- ct (protocols/get-cycle-time p positionB)) ;;this ends up being 0.
            wt     (max   (- timeremaining timeinstate) 0)
            _      (debug [:re-entry  {:cycletime ct
                                       :current-pos   current-pos
                                       :next-pos      positionB
                                       :timeinstate   timeinstate
                                       :timeremaining timeremaining
                                       :wt wt}])
            state-change {:newstate       (get-state unit positionB)
                          :duration       timeremaining
                          :followingstate nil
                          :timeinstate    timeinstate
                          }
            _          (reset! ctx
                               (->> @ctx
;; (supply/log-position! tupdate positionA positionB  unit)
                                    (supply/supply-update! {:name "SupplyStore"} unit
                                                           (core/msg "Unit " (:name unit)
                                                                     "  ReEntering at " positionB " with "
                                                                     (:bogbudget (:currentcycle unit))
                                                                     " BOGBudget."))))
            _          (reset! entity
                               (assoc unit :followoncode nil))]
        (beval  change-state-beh
                (assoc benv :state-change state-change
;;                      :position-change {:from-position positionA
;;                                        :to-position   positionB}
                       :wait-time wt
                       :next-position positionB))))

;;Function to handle the occurence of an early withdraw from a deployment.
;;when a demand deactivates, what happens to the unit?
;;The behavior will be guided by (the unit's) policy.
;;The default behavior is that a unit will check its policy to see if it CAN deploy.
;;If policy says it's okay, the unit will return to the point time of its current lifecycle.
;;We can parameterize the penalty it takes to get back into lifecycle from deployment.
;;    A usual penalty is a move to "90 days of recovery"
;;Note, we can also specify if the unit is instantly available to local demands.
;;Recovery should now be an option by default, not specifically dictated by
;;policy.

;;1)Consult policy to determine if entry back into available / ready pool is feasible.
;;TOM note 18 july 2012 -> this is erroneous.  We were check overlap....that's not the definition of
;;a unit's capacity to re-enter the available pool.

;;uuuuuuuge hack....gotta get this out the door though.
(def non-recoverable #{"SRMAC" "SRMRC" "SRMRC13"})

;;we no longer use the default +recovery-time+ shim,
;;now we consult policy or fallback to the :DefaultRecoveryTime
;;parameter.
(def policy-recovery-time
  (memo1-policy
   (fn policy-rec [p]
     (or (:recovery p) ;;srm policies have a :recovery field.
         (marathon.data.protocols/transfer-time p
          :recovery :recovered)))))

(defn recovery-time
  ([unit p]
   (or (policy-recovery-time p)
       (:default-recovery unit)))
  ([unit] (recovery-time unit (:policy unit))))

;;We need to modify this to prevent any srm units from recovering.
(defn can-recover?
  [unit]
  (let [cyc (:currentcycle unit)
        p   (:policy unit)
        rt  (recovery-time unit p)]
    (when
        (and  (not (non-recoverable (protocols/policy-name p)))
              (pos? (:bogbudget cyc))
              (< (+ (:cycletime unit) rt) (:duration-expected cyc)))
      rt)))

(befn recovery-beh {:keys [entity deltat ctx] :as benv}
  (let [unit @entity]
    (if-let [t (can-recover? unit)]
      (do (debug [:unit-can-recover (:name unit)])
          (move! :recovered t)) ;;recovery is now determined by policy or parameters.
      (let [cyc (:currentcycle unit)
            ct  (:cycletime unit)
            dur (:duration-expected cyc)]        
        (swap! ctx
               #(sim/trigger-event :supplyUpdate (:name unit) (:name unit)
                                   (core/msg "Unit " (:name unit) " Skipping Recovery with "
                                             (:bogbudget (:currentcycle unit)) " BOGBudget "
                                             ct "/" dur " CycleTime " ) nil %))
        (reset! entity (assoc-in unit [:currentcycle :bogbudget] 0))
        #_moving-beh
        reset-beh))))

;;On second thought, this is sound.  If the unit is already in overlap, it's in a terminal state..
;;For followon eligibility, it means another unit would immediately be overlapping this one anyway,
;;and the demand would not be considered filled....It does nothing to alleviate the demand pressure,
;;which is the intent of followon deployments.  Conversely, if overlap is 0, as in typical surge
;;periods, then units will always followon.  I take back my earlier assessment, this is accurate.
;;Note: We need to ensure this behavior fails if called from incompatible circumstances...
;;We can only call this on units that are actually deployed/bogging.
(befn abrupt-withdraw-beh {:keys [entity deltat] :as benv}
      (let [_    (when (pos? deltat) (swap! entity #(u/add-bog % deltat)))
            unit @entity
            ;1)
            bogremaining (- (:bogbudget (:currentcycle unit))  
                            (protocols/overlap (:policy unit)) ;;note: this overlap assumption may not hold...
                            )
            _    (debug [:abw-beh {:deltat deltat
                                   :bogremaining bogremaining
                                   :unt (:name unit)
                                   :fc (:followoncode unit)
                                        ;:unit (dissoc unit :policy)
                                   }])]
        (if (not (pos? bogremaining))
          ;makes no sense for the unit to continue BOGGING, send it home.
;          (->and [(echo [:abw->reset {:bogremaining bogremaining}])
          reset-beh
          ;])
          (->or 
             ;unit has some feasible bogtime left, we can possibly have it followon or extend its bog...
             ;A follow-on is when a unit can immediately move to fill an unfilled demand from the same
             ;group of demands.  In otherwords, its able to locally fill in.
             ;This allows us to refer to forcelists as discrete chunks of data, group them together,
             ;and allow forces to flow from one to the next naturally.         
           [followon-beh
            recovery-beh]))))

;;Policy Changes
;;==============
;;Changing policies in legacy MARATHON involves something called the "policy stack"
;;and a subscriber model where unit's "subscribe" to a parent policy (typically
;;a composite policy defined over multiple simulation periods).  Changes in the
;;period cause changes in policy, which propogate to changes in subscribers'
;;policy.  Policy changes are typically limited to "non-deployed" states or
;;dwelling states.  That is, units may not permissively change the structure
;;of their policy while "in-use" by a demand.

;;In this case, the policy change is tracked by keeping the policy change
;;stack non-empty.  When the unit cycles through a state in which policy
;;changes can occur, it finds a pending change and converts to the new
;;atomic policy.

(def infeasible-policy-change?  #{"Deployed" "Overlapping" "DeMobilization"})
(defn can-change-policy? [cycle-proportion from-pos]
  (and (<= cycle-proportion 1)
       (not (infeasible-policy-change? from-pos))))

;; 'TOM Change 13 Jul 2011
;; 'Needed to implement the transition from one policy to another.  I chose to add a state to handle just this.
;; 'Visual analysis showed that PolicyChange looks a lot like Spawn, in that when a unit changes policies, it must change
;; 'a lot of its internal state to follow the new policy.  The result of the policy change is:
;; '   1: The unit's cycle time is normalized, and then transformed into the relevant cycletime in the new policy.
;; '   2: The unit's position "may" change to reflect its position in the new policy.
;; '   3: The unit's location "may" change to reflect its location in the new policy.
;; 'TOM Change 20 April:
;; '   4: The unit's BOGBudget "may" change to reflect either increased, or decreased, BOGBudget.
;; 'TOM Change 24 April:
;; '   5: The unit's BOGBudget and MAXBOG may only change (increase) as the result of a policy change.
;; '   6: Policy changes can NOT happen during terminal states:
;; '       [Deployed {Bogging, Overlapping}, Demobilizing]


;; 'If NOT deployed (bogging, overlapping) or in a terminal state (demobilizing), then entities can change policy immediately.
;; 'Otherwise, units change policy upon next reset (change is deferred).

;; 'Assumes that the new policy is already set for the unit (i.e. the unitdata is pointing toward the new policy).
;; 'Ideally, an outside agent will have modified the unit's policy, and subsequently told it to changestates to a policy-change
;; 'state.

;; 'Net effect is that policy changes to the same policy are idempotent.
;; 'State to control how a unit acts when it changes policy.
;; 'Note -> we extract the next policy from the unitdata's policy stack.

;; 'TOM note -> figure out how to change this for the deployed population...they have negative cycle
;; 'times.

;; 'Note -> this assumes we have compatible policies, or at least policies that have a cyclical
;; 'rotational lifecycle.

;; Function PolicyChange_State(unit As TimeStep_UnitData, deltat As Single) As TimeStep_UnitData
;;WIP Nov 2016
(befn policy-change-state ^behaviorenv {:keys [entity wait-time tupdate policy-change ctx]
                                        :as benv}
      (when policy-change ;;we have a change.
        (if (u/waiting? @entity)
          (do (debug [:deferring-policy-change-while-waiting])
              defer-policy-change) ;;units in waiting must defer policy changes!
          (let [next-policy (:next-policy policy-change)
                unit @entity
                tnow tupdate
                _    (assert (pos? (protocols/bog-budget next-policy)) "No bog budget!")
                current-policy (:policy unit)
                ;;'TOM Change 20 April -> We need to separate the unit's experienced
                ;;'cycle length vs the NOMINAL cycle duration, which exists in
                ;;'POLICY SPACE.  In composite rotational policies, the NOMINAL cycle duration
                ;;'changes when Atomic policies change.  Specificallly, we map the unit's position
                ;;'or coordinates in the current atomic policy to coordinates in the new policy.
                ;;'The unit's actual experienced lifecycle, i.e. its cycletime property, is not
                ;;'an accurate mapping between policies.  The implicit assumption is that when
                ;;'mapping from one policy to another, if the policies have differing cycle lengths
                ;;'then there is a discount or exchange rate between the policies, such that time
                ;;'spent in one policy is NOT equal to time spent in another.  However, our
                ;;'unit's cyclelength property is not subject to this, since it technically
                ;;'exists OUTSIDE of the policy view of time.  The cyclelength property reflects the
                ;;'actual time a unit has spent, under ANY policy, until it has reset or started a
                ;;'new cycle.
                
                ;;'Prior to 19 April 2012, The unit's ability to deploy, via the CanDeploy method,
                ;;'depended on it's position in the current policy as a function of the cyclelength property.
                ;;'We should prefer the duration of the current cycle record, which is an accurate reflection
                ;;'of the relative time in the unit's current policy.
                ;;'TOM Change 20 April 2012
                cycletimeA (:cycletime      unit)
                PositionA  (:positionpolicy unit)
                ;; _          (println [:name (:name unit) :cycletimeA cycletimeA
                ;;                      :positionA PositionA (assoc benv :ctx nil)])                         
                _          (assert (not (neg? cycletimeA))
                                   (str {:msg "Cycletime should not be negative!"
                                         :cycletime cycletimeA
                                         :unit (:name unit)
                                         :t tupdate}))
                CycleProportionA  (core/float-trunc (/ cycletimeA  (protocols/cycle-length current-policy))
                                                    6)
                ;;'TOM change 23 April 2012 -> No longer allow units that are De-mobilizing to enter into available pool.
                ]
            (->or [(->and [(->pred (fn [_] (can-change-policy? CycleProportionA PositionA)))
                           (->alter #(assoc % :policy-change {:cycletime cycletimeA
                                                              :current-policy current-policy
                                                              :next-policy next-policy
                                                              :proportion CycleProportionA
                                                              :current-position PositionA}))
                           apply-policy-change])                 
                   defer-policy-change])))))


;;TODO: Add this?

;;'Craig add 10 May 2016 . We have some units changing policies from a longer BOG budget
;;'to a shorter BOG budget.  We are running into issues when a unit's new BOG budget is < Overlap
;;'and then this unit got deployed and ended up with a negative BOG budget throwing negativeErr
;;If .CurrentCycle.bogbudget <= .policy.overlap Then 'this matches our check in AbruptWithdraw_State
;;  Set unit = Reset_State(unit, deltat)
;;  .parent.UpdateDeployStatus unit
;;End If


          ;;This automatically gets checked during move!...
;;         MarathonOpSupply.UpdateDeployStatus simstate.supplystore, unit, , , simstate.context

;;         'Adopt Policy B.
;;         'Policy A ->
;;         '    Find relative CT = ct/CLengthA
;;         'Policy B ->
;;         '    Find relative positionB = pos(RelativeCT * CLengthB)
;;         'Movingstate from PositionA to relative PositionB.
;;         'Update with delta0.
;;         'TOM Change 2 Sep -> moved this north so that we can use the policy stack as a flag in unit's
;;         'ChangeCycle logic.  Check for sideeffects

;;         .policyStack.Remove 1

;;         SimLib.triggerEvent UnitChangedPolicy, .name, .policy.AtomicName, "Unit " & .name & " changed policies: " & _
;;             policynameA & ":" & cycletimeA & "->" & policynameB & ":" & CycleTimeB, , simstate.context
          

;;SET UP A STATECHANGE           

;;         SimLib.triggerEvent supplyUpdate, .name, .name, "Policy Change Caused Supply Update for unit " & .name, , simstate.context            
;;         Set PolicyChange_State = ChangeState(unit, nextstate, 0, newduration)


;;         'NOTE -> I may need to consider changing location here.....
                             
;;The unit's cycle cannot project onto another cycle.  We need to defer policy change until reset.
;;leave the policy on the stack.  Catch it during reset.
;;TOM change 2 Sep 2011 -> we modify the cyclerecord to reflect changes in expectations...
;;This is not a replacement...
;;WIP Nov 2016
(befn defer-policy-change {:keys [entity ctx tupdate policy-change] :as benv}
      (when policy-change
        (let [_   (debug [:deferring-policy-change])
              {:keys [next-policy]} policy-change
              unit @entity
              uname (:name unit)
              _     (swap! ctx
                           #(core/trigger-event
                             :AwaitingPolicyChange uname  (marathon.data.protocols/atomic-name
                                                           (:policy unit))
                             (core/msg "Unit " uname " in position " (:positionpolicy unit)
                                       " is waiting until reset to change policies")
                             nil %))
              ;;marked the deferred policy change.
              _     (swap! entity #(assoc % :deferred-policy-change
                                          (select-keys  policy-change [:next-policy])))]
          (->alter (fn [benv] (assoc benv :policy-change nil))))))

(befn try-deferred-policy-change {:keys [entity ctx tupdate] :as benv}
      (when-let [pc (:deferred-policy-change @entity)]
        (let [_ (debug [:applying-deferred-policy-change])
              _ (swap! entity assoc :deferred-policy-change nil)]
          (->seq [(->alter (fn [benv] (assoc benv :policy-change pc)))
                  policy-change-state]))))
            
      ;;         SimLib.triggerEvent AwaitingPolicyChange, .name, .policy.AtomicName, "Unit " & _
      ;;                 .name & " in position " & .PositionPolicy & " is waiting until reset to change policies", , simstate.context
      ;;         Set unit = RevertState(unit)
      ;;         'We updated the unit in the process
      ;;         SimLib.triggerEvent supplyUpdate, .name, .name, "Policy Change Attempt Caused Supply Update for unit " & .name, , simstate.context


;;Basic Unit Behaviors (or "States....")
;;=====================================

;;entities have actions that can be taken in a state...
(def default-statemap
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


;;PERFORMANCE NOTE: HotSpot - used val-at macro to inline method calls.
;;lookup what effects or actions should be taken relative to
;;the current state we're in.  This is kind of blending fsm
;;and behaviortree.
(befn do-current-state {:keys [entity statedata] :as benv}
      (let [;state (:state @entity)
            state  (:state  (deref!! entity)) ;;slightly faster using keyword as function call.
            state-map (or (:statemap entity) default-statemap)]
        (if (set? state)  ;entity has multiple effects...
          ;;MEGA-HACK:This a serious hack to prevent double-counting of bog when we have
          ;;state-sets.  Alone, either overlapping or bogging confers collecting bog time,
          ;;and in legacy policies are mutually exclusive.  However, for SRM policies,
          ;;we have the possibility of bogging/non-bogging, as well as being in an
          ;;overlap state.  This leaves us with a conundrum relative to our default
          ;;legacy meanings of bog and overlap.  What we can do is ensure that if
          ;;bogging is present, we just skip overlapping if we ever encounter a
          ;;state-state.  This is practical, but somewhat brittle....probabtately
          ;;a better idea to encode the meaning of states better - like [:bogging :overlapping]          
          (let [stats (r/filter identity                                 
                                (r/map (fn [s] (val-at state-map s)) (disj state :overlapping)))
                ]
            (->seq stats))
          (get state-map state))))

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

(defn handle-messages-with [handler]
  (befn handle-messages ^behaviorenv {:keys [entity current-messages ctx] :as benv}
      (when current-messages
        (reduce (fn [acc msg]                  
                  (message-handler msg (val! acc)))
                (success (assoc benv :current-messages nil))
                current-messages))))

;;The global sequence of behaviors that we'll hit every update.
;;These are effectively shared behaviors across most updates.
(def global-state
  (->seq [(echo :aging)
          age-unit          
          (echo :aged)
          moving-beh]))

(befn up-to-date {:keys [entity tupdate] :as benv}
      (let [e (reset! entity (assoc @entity :last-update tupdate))]
        (echo [:up-to-date (:name e) :cycletime (:cycletime e) :last-update (:last-update e) :tupdate tupdate
               :positionpolicy (:positionpolicy e)])))

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

;;if we have a message, and the message indicates
;;a time delta, we should wait the amount of time
;;the delta indicates.  Waiting induces a change in the
;;remaining wait time, as well as a chang
(befn wait-in-state ^behaviorenv [entity current-message ctx]
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

;;wire in functionality here for a unit to invoke its own
;;deployment order...
;;From here on, the system will append a deployment order to
;;the unit, and send the unit a message to update.
;;The unit will handle the message by appending a
;;deployment order to its state and invoking an update.
;;This way, we handle messages first, which preps the
;;behavior environment to respond to stimulii (like
;;the presence of a deploy order)
(defn deploy-to [o benv]
  ;;stub
  (success benv))

(befn try-deploy ^behaviorenv {:keys [entity] :as benv}
      (when-let [o (:deploy-order @entity)]
        (deploy-to o)))

;;This is kind of weak, but I don't have a better solution at the moment...
(do (println [:setting-defaults])
    (reset! base/default-behavior roll-forward-beh))

;;aux function to help us add a breadcrumb for
;;the location-based behavior updates.
;;Some locations have overlap.  If so, we look for this
;;to see if the move is prescribed.  We store this as a
;;component in the entity.
(defn prescribe-overlap! [benv t overlap state locname]
  (if (and overlap (pos? overlap))
    (let [entity (:entity benv)]
      (do (debug [:prescribing-overlap (:name @entity)  overlap t])
           (swap! entity  assoc :prescribed-move
                 {:state-change
                  {:newstate       state
                   :duration       overlap
                   :followingstate nil
                   :timeinstate    0}
                  :overlapping-position [locname :to] ;true
                  :t t}
                 )
          benv))
    benv))
  
;;SRM bs...
;;SRM takes a different view of unit behavior.
;;Most importantly, for AC units (and deploying RC units),
;;the behavior looks at demand to determine position
;;changes, state-changes, duration, etc., rather than look
;;at the policy.

;;When not in a mission state, the default behavior does
;;provide a cyclical routing, even for AC (At the moment,
;;but that crap will probably change like everything else).
;;We should be able to inject a supply of units that
;;follow the baseline SRM policy, with no demand, and
;;Just have them spawn and run through policy changes.
;;The SRM behavior only really varies upon deployment...
;;so we can create special SRM-specific behaviors that
;;read information about the demand and use it
;;to schedule changes.  For now, there is no
;;notion of recovery...

;;These differences mean we need to handle
;;local-demand effects if deployed....

;;For any movement, we need to check to see if
;;there are effects or guidance associated with the
;;place we're moving to.  Some places tell us what
;;to do, outside of our policy.
;;The only way we can get here is if there is a location-policy
;;in the environment.  How does it get there?
;;TODO_Have the location push behaviors onto some kind of
;;stack.  This could be very powerful (and common), in that
;;the behavior would evaluate its top-most behavior first
;;(i.e. do-current-state), and pop the behavior once
;;the time expired.
(defn location-based-state [u state]
  (let [s (get-state u state)
        s (if (set? s) s #{s})]
    s))

(befn location-based-beh {:keys [entity location-based-info ctx] :as benv}
  (when  location-based-info
    (let [{:keys [name MissionLength BOG StartState EndState overlap
                  timeinstate]}
          location-based-info
          ;;StartState is really a policy position....
          start-state (location-based-state @entity StartState)          
          newstate    (if BOG (conj start-state :bogging) start-state)
         ;;we need to schedule a state change.
          ;;and a location-change...
          _ (swap! entity assoc :location-behavior true)
          followingstate  (if (pos? overlap)
                            (conj newstate :overlapping)
                            (location-based-state @entity EndState))
          state-change {:newstate       newstate
                        :duration       (- MissionLength  overlap)
                        :followingstate followingstate
                        :timeinstate    (or timeinstate 0)}          
          location-change  {:from-location  (:locationname @entity)
                            :to-location     name}
          position-change {:from-position (:positionpolicy @entity)
                           :to-position   StartState}
          ;;add the ability to check for prescribed moves...
          ;;if the demand prescribes one, then we go ahead and schedule it with
          ;;the entity...                               
          wt           (- MissionLength overlap)
          _  (debug [:location-based
                       {:name (:name @entity)
                        :state-change state-change
                        :location-change location-change
                        :wait-time wt
                        :next-position StartState}])
          ]
          (beval  change-state-beh
                  (-> benv
                      (prescribe-overlap!  (+ (:tupdate benv) wt)
                                           overlap
                                           followingstate
                                           name)
                      (assoc 
                       :state-change state-change                          
                       :location-change location-change
                       :position-change position-change ;new
                       :wait-time     wt
                       :next-position StartState))))))

;;Another potential garbage leak!
(def wbm (atom nil))
(befn wait-based-beh {:keys [entity statedata wait-based-info ctx] :as benv}
  (when  wait-based-info
    (let [{:keys [demand wait-time wait-state]} wait-based-info
          name (:name demand)
          state-change {:newstate       wait-state
                        :duration       wait-time
                        :followingstate (:state @entity)
                        :timeinstate    0}
          location-change  {:from-location  (:locationname @entity)
                            :to-location     name}
 ;         position-change {:from-position (:positionpolicy @entity)
 ;                          :to-position   StartState}
          _  (debug [:wait-based
                       {:name (:name @entity)
                        :state-change state-change
                        :location-change location-change
                        :wait-time wait-time}])
          ;; _ (throw (Exception. (str [:about-to-wait  {:name (:name @entity)
          ;;                                             :state-change state-change
          ;;                                             :location-change location-change
          ;;                                             :wait-time wait-time}])))
          ]
      (->seq [(->alter #(assoc % :state-change state-change
                               :location-change location-change))
              change-location
              change-state-beh
              (->alter (fn [benv]
                         (let [u (deref (:entity benv))
                               _ (debug [:deployable-changed! :waiting
                                         :deployment-index (:deployment-index u)])
                               _ (swap! (:ctx benv) #(supply/update-deploy-status u nil nil %))
                               ;_ (reset! wbm u)
                               _ :ballz #_(throw (Exception. (str [:ballz])))]
                            benv)))]))))



;;All our behavior does right now is spawn...
;;The only other changes we need to make are to alter how we deploy entities...
;;We can actually handle that outside of the unit's deployment....
;;Possibly include it as a message type...
;;Have a special message handler for it...
;;[genius]

;;If we have an location-based-policy to apply, we can
;;tell the unit via messaging...
;;We typically tell the unit form outside, after we've
;;set it up and everything...

;;SRM behavior overrides some functionality for the base behavior.
(befn srm-beh []
      spawning-beh
      
      ;(throw (Exception. (str "SRM Behavior doesn't do anything!")))
      )

(do (println [:setting-srm])
    (swap! base/behaviors assoc
           "SRM" roll-forward-beh ;same thing.
           ;srm-beh
           ))


      


(comment ;old version
(befn do-current-state {:keys [entity statedata] :as benv}
      (let [;state (:state @entity)
            state  (:state  (deref!! entity) ) ;;slightly faster using keyword as function call.
            state-map (or (:statemap entity) default-statemap)]
        (if (set? state)  ;entity has multiple effects...
          (let [stats (r/filter identity (r/map (fn [s] (get state-map s)) state))]
            (->seq stats))
          (get state-map state))))
)



(comment ;OBE

(defn update-unit 
  "Computes a new simulation context given a specific unit to update, 
   an elapsed time, and an optional time of update.  tupdate is inferred
   to be the current simulation time if none is supplied."
  ([unit deltat ctx]        
     (update-unit unit deltat (sim/current-time ctx) ctx))
  ([unit deltat tupdate ctx]
     (->>  ctx  
           (load-entity! unit deltat tupdate)
           (roll-forward-beh) ;update the unit according to the change in
                                        ;time.
           (error-on-fail)    ;unit updates should never fail.       
           (second  ;result is in terms of [:success|:fail ctx], pull out
                                        ;the ctx
            )
           (commit-entity!)
;           (clear-bb)
           )))

;;We'll replace these; for now the units will automatically
;;try to update themselves if possible.

;;Debatable...just invokes roll-forward-beh; I think we can ensure that
;;roll-forward is always invoked first...

;;Re-evaluate the need for this....can we synchronize from outside?
;;ideally, we just keep track of the unit's last update....
(defn sync
  "Utility function.  Synchronize the unit to the current simulation time.  
   If the last update occured before the current time, we roll the unit forward 
   by the delta between the last update and the current time."
  [unit ctx]
  (let [tprev (or (sim/last-update (:name unit) ctx) 0)
        tnow  (sim/current-time ctx)]
    (if (= tprev tnow)
      (log! (str "unit " (:name unit) "is up to date") ctx)
      (log! (str "Synchronizing unit " (:name unit) " from " tprev " to " tnow)
            (update-unit unit (- tnow tprev) tprev ctx)))))

;;Synchronizes the unit to the current time, then applies a time 
;;delta, then processes/records the unit's time of update.
(defn update 
  "Entry point for computing behavior-based unit updates.  Fundamental 
   API function for processing unit entities.  Synchronizes the unit to 
   the current simulation time, then computes the new simulation context 
   resulting from the entity behavior over an elapsed deltat (from current
   simulation time)."
  [unit deltat ctx]
  (let [nm (get unit :name)]
    (->> (sync unit ctx)
         (update-unit unit deltat)
         (u/unit-update! nm (core/msg "Updated " nm)))))
)
