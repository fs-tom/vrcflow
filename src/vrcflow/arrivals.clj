;;We need a consistent way to formalize arrivals and entity batches, which may
;;be pre-determined (ala an arrivals file or sequence) or
;;stochastically-generated (via variable interarrival and batch-size fns.)
(ns vrcflow.arrivals
  (:require [spork.entitysystem.store :as store]
            [vrcflow.services :as services]))
(comment 
;;What's a batch? Batches basically describe the arrival of one or more entities
;;into the system at a point in time. At a minimum, we want to know: t - when
;;the batch arrives pending - what's in the batch behavior - the behavior the
;;batch should follow?

;;The original implementation assumed a batch would be defined as a map
;;providing parameters for a stochastically-generated batch.

;;default batching function assumes current-time and builds a batch
;;stochastically based on random interarrival time and random batch-size (if
;;specified). next-batch should take 2 arity, t and ctx, to allow other batch
;;functions to access the context, say to compute deferred batches
;;(pre-scheduled arrivals).

;;this is the prototypical stochastic batch function for generating
;;randomly-arriving entities of variable size. The original implementation
;;focused on variable-sized and variable-timed batches as a function of
;;interarrival-times and random batch-sizes... generate an update for itself the
;;next time... next-batch now accepts a size

#_(defn next-batch
  "Given a start time t, and an interarrival time
   function f::nil->number, generates a map of
   {:n arrival-count :t next-arrival-time} where t
   is computed by sampling from f, such that the
   interarrival time is non-zero.  Zero-values
   are aggregated into the batch via incrementing
   n, accounting for concurrent arrivals (i.e. batches)."
  ([t f]
   (loop [dt (f)
          acc 1]
     (if (pos? dt)
       {:n acc :t (+ dt t)}
       (recur (f) (inc acc)))))
  ([t f b] (assoc (next-batch t f) :behavior b))
  ([t f size-f b]
   (-> (loop [dt (f)
              acc (size-f)]
         (if (pos? dt)
           {:n acc :t (+ dt t)}
           (recur (f) (+ acc (size-f)))))
       (assoc  :behavior b))))


;;we should decouple the absolute time from the deltat...
;;rather than generating an exact time, just generate
;;the batch-size and deltat....
;;We can tack on the exact time in a later step...

;;break down batch generation into a few discrete steps:
;;We need to know how big the batch is.
;;when it's arriving...
;;That information alone is enough for a higher-order fn
;;to interpret into a set of entities arriving at
;;an absolute time.



(defn next-batch
  "Given a start time t, and an interarrival time
   function f::nil->number, generates a map of
   {:n arrival-count :t next-arrival-time} where t
   is computed by sampling from f, such that the
   interarrival time is non-zero.  Zero-values
   are aggregated into the batch via incrementing
   n, accounting for concurrent arrivals (i.e. batches)."
  ([t f]
   (loop [dt (f)
          acc 1]
     (if (pos? dt)
       {:n acc :t (+ dt t)}
       (recur (f) (inc acc)))))
  ([t f b] (assoc (next-batch t f) :behavior b))
  ([t f size-f b]
   (-> (loop [dt (f)
              acc (size-f)]
         (if (pos? dt)
           {:n acc :t (+ dt t)}
           (recur (f) (+ acc (size-f)))))
       (assoc  :behavior b))))


;;The default implementation of batch->entities
;;uses this specification to generate n entities....
(defn batch->entities [{:keys [n t behavior] :as batch
                        :or {behavior (spork.ai.behavior/always-fail "no-behavior!")}}]
  (for [idx (range n)]
    (let [nm (str t "_" idx)]
      (merge (client nm :arrival t)
             {:behavior behavior
              :spawning? true}
             ))))

;;So, next-batch basically generates one-or-more entities scheduled to arrive at
;;a time (+ dt) from the start-time, where dt is potentially drawn from a random
;;distribution. If batch-size is not specified, we assume 1, with the exception
;;that we could exceed 1 if interarrival times are identical. That is, if the
;;arrivals "happen" to occur naturally at the same time, we add to the batch
;;until they don't.

;;The result is a map {:keys [n t]} that specifies the batch-size and arrival
;;time of the next batch.


;;This function is then interpreted by another, batch->entities, which
;;transforms the spec into multiple entities.

;;We have another method, which defines batches of multiple entities ahead of
;;time. In this case, the batch is comprised of {:keys [t behavior batch]} as
;;before, but the batch entry is now a sequence of multiple entity-records
;;intended to be added to the entity store.


;;Note: this is identical to the original, with the absense of the keyword :n.
;;Instead, we have a batch pre-made
#_(defn batch->entities [{:keys [t behavior batch] :as b
                          :or {behavior beh/client-beh}}]
    (for [[idx e]  (map-indexed vector batch)]
      (let [nm (str t "_" idx)]
        (merge (services/client nm :arrival t)
               {:behavior behavior
                :spawning? true}
               e)
        )))

;;This covers the use-case where we have multiple entities pre-defined to arrive
;;at a certain time. It's common to generate these batches programatically or
;;read them from an arrivals file.

;;Currently, we assume either the stochastic-batches, or the multiple-entity
;;batches, and use different implementations of batch->entities for them.

;;These functions are handled inside of vrflow.core/process-arrivals, which uses
;;an arrivals entity {:keys [pending arrival-fn next-batch]} that maintains the
;;current pending batch, and possibly a custom next-batch function.

;;The arrivals entity projects the pending batch onto pending entities, using
;;batch->entities, schedules their arrival, and computes the new batch using
;;next-batch.

;;A problem with the naive implementation of multiple entity arrivals (the
;;deterministic case) is that we currently generate "all" entity arrivals
;;a-priori, and add all entities into the system up-front. This isn't terrible,
;;but it complects things like the batch representation.

;;In the default case, we have a map {:keys [n t behavior]} where n determines
;;the batch-size of entities to-be-generated. Contrast this with the
;;deterministic representation {:keys [batch t behavior]}, where the batch is
;;already computed.

;;Perhaps a better representation is {:keys [entities n t behavior]} If entities
;;already exists, then we batch->entities returns them directly. If-not, then we
;;use n to randomly generate a sequence of entities.


;;On the arrivals side, there are substantial differences: During initial
;;processing/context creation, the arrivals are processed differently based on
;;if they are a sequence of batches, or an actual batch. A sequence of batches
;;goes through schedule-multiple-batches, and currently bypasses the arrivals
;;entity.... All entities are manually scheduled for arrival, update requests,
;;and added to the entity-store. In the batch case, we assoc the batch onto the
;;:pending component of the arrivals entity, and request an update at the time
;;of the batch for the arrivals entity. This hooks into the arrivals system and
;;lets us process normally...

;;Can we unify these concepts, hook into the arrivals system, and schedule
;;entities for batch arrival on-demand vs. a-priori?

;;a) unify batch representation, avoid custom batch->entities If we unify the
;;batch representation to include... an :entities key (currently :batch) then we
;;can fold in the entities->batch function to just pre-computed entities vs.
;;generating, otherwise rely on the :n key (should be batch-size) to
;;stochastically generate entities from the current batch, and derive the
;;next-batch.

;;b) allow next-batch to account for pending pre-computed entities. We'd like to
;;have next-batch play nice with the arrivals entity, so that the first batch is
;;stored in the :pending component. We'd always like to know what's scheduled by
;;looking at the :pending component of arrivals.

;;In the case of recurring stochastic batches, we have a pending batch, from
;;which future batches will be computed. We may want to examine this to allow
;;expressing things like finite-batches (generate until some condition is met)

;;In the case of deterministic batches, we want to continue drawing from the
;;remaining batches, scheduling them as the next pending batch.

;;One option is to treat :pending as polymorphic. If it's a stochastic batch, we
;;create entities and - maybe - compute a next-batch.

;;sidebar - stochastic batches should have an explicit stopping criteria...

;;If it's a sequential batch, we want to draw the next batch via first. Process
;;the entities via batch->entities, then update the pending arrivals by popping
;;the first batch.

;;So, next-batch should be able to operate on the arrivals entity.

;;These are both views of a batch-sequence. In the stochastic case, we produce a
;;sequence of batches by iterating on the current batch, generating the next via
;;applying a transition function to the current pending batch (next-batch).

;;In the deterministic case, we do something similar, our transition function is
;;just next....

;;Maybe it makes sense to have a :pending and a :scheduled component.

;;The state-xn function is simpler. :pending is always the next operable batch
;;of entities. :scheduled is the rest of the sequence. to get the next pending,
;;we get the first of scheduled. To get the next scheduled, we take the rest of
;;scheduled (or pop).

;;While batch-time = current-time, schedule arrivals for the batch.

;;This lets us abstract better...

;;What's a decent api? request-arrival::batch -> ctx -> ctx

;;Do we have a different API for generative arrivals.
;;generate-arrival::initial-batch -> ctx -> ctx

;;Or do we provide a sequence of generated arrivals and live with that?

;;pull-based schedule-arrivals::batch seq|chan -> ctx ->ctx

;;given a sequence (either realized or generated lazily) of batches {:keys [t n?
;;entities? behavior]} registers the first batch as :pending batch, storing the
;;remainder of the sequence as :remaining


#_(defn schedule-arrival
  "Given a batch order, schedules new arrivals for ctx."
  [batch ctx]
  (->> batch
       (ensure-behavior ctx)
       (store/assoce ctx :arrival :pending)
       (sim/request-update (:t batch) :arrival :arrival)))

;;so, we always make sure our batches are seqs of maps.

;;This way, next-batch is simply
;;(first remaining).
;;Processing arrivals equates to
;;updating :remaining to rest of
;;remaining.
#_(defn schedule-arrivals [batches ctx]
  (let [{:keys [pending arrival-fn next-batch remaining]
         :as arr}  (store/get-entity ctx :arrival)
        head       (first batches)
        remaining  (rest :batches)]
    (->> head
         (ensure-behavior ctx)
         (assoc arr :remaining remaining :pending)
         (store/add-entity ctx :arrival))))

#_(defn next-batch   [t ctx]
  (services/next-batch t
                       next-arrival next-size default-behavior))

;;I don't think we need to add behaviors...
#_(defn next-batch
  "Given a start time t, and an interarrival time
   function f::nil->number, generates a map of
   {:n arrival-count :t next-arrival-time} where t
   is computed by sampling from f, such that the
   interarrival time is non-zero.  Zero-values
   are aggregated into the batch via incrementing
   n, accounting for concurrent arrivals (i.e. batches)."
  ([t f]
   (loop [dt (f)
          acc 1]
     (if (pos? dt)
       {:n acc :t (+ dt t)}
       (recur (f) (inc acc)))))
  ([t f b] (assoc (next-batch t f) :behavior b))
  ([t f size-f b]
   (-> (loop [dt (f)
              acc (size-f)]
         (if (pos? dt)
           {:n acc :t (+ dt t)}
           (recur (f) (+ acc (size-f)))))
       (assoc  :behavior b))))

;;Batches are really stochastic maps...

;;starting from an initial time...
;;generate a stream of batches.  In practice,
;;we're not holding onto the head, so this should
;;be memory-safe.
#_(defn ->stochastic-batches
  ([t0 arrival-f size-f beh]
   (let [b     (next-batch t0 arrival-f size-f beh)
         tnext (:t b)]
     (lazy-seq
      (cons [b] (->stochastic-batches tnext arrival-f size-f beh))))))

;;All we "really" need though, is an abstract generator..
;;i.e. next-batch or draw.

;;We may be need abstractions for ordered-batch...
;;So, if it's a fn, we can call the fn on the context
;;to get the next batch.
;;If it's a seq, we do first/rest to traverse batches.

;;I think it's preferable to start with a functional
;;approach that utilizes the context (say for
;;prng or other information), to generate new
;;batches incrementally.

;;If we have truly independent batches, we can just
;;wrap an iterated seq (or the like) behind
;;a function like (fn [ctx] (next (-> ctx get-entity :arrival :pending)))

;;What about the rest of the sequence?  If we're immutable,
;;we need to store it in the state somewhere.

;;If we don't care about actually having the entire sequence,
;;we could us an atom for side effects...thus giving us
;;a stream of (ctx -> batch) invocations.  Probably
;;better for memory safety...

;;So, when we go to generate the next value, it should
;;be a function of ctx -> [result ctx]

;;If there's a result, then we can capture that and update the
;;ctx...


(defprotocol IBatchGenerator
  (pop-batch  [g ctx]))

(defn get-gen [ctx nm]
  (store/gete ctx nm :seed))
(defn update-gen [ctx nm res]
  (store/assoce ctx nm :seed res))

(defn ->gen
  "Defines a function that updates an entity in a context, where entity has a
  :seed component, the entity will store the current :seed there. Uses a
  function ctx->seed to fetch said seed value, generates a element in the
  sequence from the previous seed. "
  ([f seed ctx->seed batch->ctx]
   (reify IBatchGenerator
     (pop-batch [g ctx]
       (let [prev (or (ctx->seed ctx) seed)
             nxt (f prev)]
         [nxt (batch->ctx ctx nxt)]))))
  ([f seed]
   (let [nm           (keyword (str "batcher_" (gensym)))
         get-batch    (fn get-batch [ctx] (or (get-gen ctx nm) seed))
         commit-batch (fn commit-batch [ctx res]
                        (update-gen ctx nm res))]
     (->gen f seed get-batch commit-batch))))

;;so, this is a way to define sequential generators that live
;;inside of entity components.
(defn ->seq-gen [xs]
  (let [p (->gen (fn [{:keys [head remaining] :as seed}]
                     {:head (first remaining)
                      :remaining (rest remaining)})
                   {:head nil
                    :remaining xs})]
    (reify IBatchGenerator
      (pop-batch [g ctx]
        (let [[res ctx] (pop-batch p ctx)]
          [(:head res)
           ctx])))))

;;maybe another way to do this is to have a seeded-entity?
;;{:seed x
;; :next-context f}  ;;transitions...

;;so popping is just applying next-context to the ctx and current seed.

;;this is sounding like spork.entitysystem.ephemeral
(defn ->seeded [id seed get-next-context]
  {:name id
   :seed seed
   :get-next-context get-next-context})

(defn current-seed [ctx id]
  (store/gete ctx id :seed))

#_(defn pop-seed [ctx id]
  (if-let [f (store/gete ctx id :get-next-context)]
    (f ctx)
    (th)))

#_(defn add-seed
  ([ctx id seed f2]
   (store/add-entity ctx (->seeded id seed f2)))
  ([ctx id f]
   (store/add-entity ctx
                     (->seeded id
                               (f)
                               (fn [_ ctx] (store/assoce ctx id :seed (f)))))))


;;Primitive Batches
;;=================
;;Revisiting the notion of arrivals and batchin in our simulation....
;;A batch is a representation of one or more entities, placed in simulation
;;time.  It's meant to serve as a placeholder for entities arriving into
;;the system at a later date.

;;Primitive batches consist of nothing but a time and the entities
;;sleighted to arrive.

;;{:t :entities}

;;Arrivals Means Schedule of Entities
;;===================================
;;An arrivals schedule provides a sequence of batches.
;;So, if we read from a file, a bunch of individual entities that
;;are supposed to spawn into the system with a t field, then
;;we can create said schedule in multiple ways, all of them ending
;;with a large sequence of entities ordered by arrival time.

;;Random Batch => higher order fn makes primitive batch
;;=========================================================
;;What if we don't want to reify our entire entity model in the
;;arrivals file.  What if some elements (particularly arrival time)
;;are best left to be generated at runtime?

;;A random batch provides some specification of how to generate
;;a primitive batch at runtime.  The default mechanism is to
;;specify batch-size and other generic characteristics, then
;;derive an entity batch from there (let the sim decide how
;;to fill in the details about what entities are).

;;The problem I created was complecting the random-batch with
;;arrivals generation.  That is, we generate the next-batch
;;from the current batch perpetually.

;;Random Arrivals => fn that computes an arrivals schedule from seed
;;==================================================================
;;To decomplect from our scheduling of entities, we'll have
;;a static schedule and a dynamic schedule.
;;All of these contribute to arrivals, but dynamic schedules
;;are associated with some seed (likely a random batch).

;;Generalizing random and static schedules into a coherent
;;arrivals stream.
;;===============

;;So the trick is to handle both dynamic and static arrivals
;;in a unified fashion.  If I have an arrivals entity, it will
;;have a :pending component.  :pending could be one or more
;;batches.  We need to extract arrivals from pending....
;;I'd like to view pending as a source of one or more arrival
;;streams.  Typically, when we create an arrival batch, we'll
;;have queud an update for the :arrival entity, so we
;;should only be checking when we actually know we have
;;a batch arriving a-priori.

;;From the perspective of handling an arrival, we need to know
;;the consequences of the arrival:
;;  a: which entities arrived in the batch?
;;  b: will there be more batches after this?

;;We should separate the questions into two systems:
;;  a - handling current arrival batches.
;;  b - computing next arrival batches.

;;In the simple-case of primitive entities:
;;  a: the entities associated with :entities key
;;  b: Only if there are pending arrivals for this schedule.
;; (how do we know what the batch schedule is?)
;;  - We should formalize entities into batches.
;;    Create a :schedule to identify which source
;;    produced said entity, and a :batch-id to indicate
;;    the unique batch it came from?

;;In the case of random-entities:
;;  a - The entities generated by batch->entities (from the ctx)
;;  b - If the function (next-batch ...) produces
;;      a new batch.

;;Synthesis: Arrivals via schedules via static and random batches
;;===============================================================
;;So, we discover it may be useful to formalize schedules as entities.
;;We then have the arrivals entity consult known schedules to answer
;;questions a, b.  This generates a sequence of entities to spawn,
;;handled by the normal arrivals process, updated schedules, and
;;updates for the arrivals entity.  If any child schedule has
;;an update, the arrivals entity has an update.

;;This way, we formalize our arrivals system to operate on one or more
;;child entity batch schedules.

;;Entity batch schedules define an abstract stream of entity batches, and
;;provide operations to compute the current batch, as well as the next batch.

;;Static schedules read from an underlying sequence of primitive batches to
;;provide a stream of entity batches over time.

;;Dynamic schedules generate a stream of entity batches over time based on some
;;initial seed, and a transition function.

;;Both static and dynamic schedules reify down to an interleaved sequence of
;;entity orders by time. This is the entity schedule.

)

;;Basic protocols
;;===============
#_(defprotocol IPrimitiveBatchSchedule
  (next-batch    [schd])
  (current-batch [schd]))

;;say we have static entities from an arrivals file,
;;or some other source.
#_(def ents
    [{:t 1 :entities [{:name :bilbo
                       :age 180}
                      {:name :sam
                       :age 182}
                      {:name :grimlock
                       :age  :me-grimlock-me-no-care}]}
     {:t 50 :entities [{:name :foxy
                        :age 2}
                       {:name :beowulf
                        :age 2}
                       {:name :hearty
                        :age  :3}]}])

;;This conveys two batches of 3 entities a-piece.
;;We'd like to load these into an arrivals schedule.

;;The simplest implementation is to create an entity
#_(def the-schedule
    {:name    :some-arrival-schedule
     :pending ents
     :tstart (first (map :t ents))
     })

;;if we have an entity for :arrival, we can register the-schedule
;;as a schedule...

#_(def arrival-entity
    {:name :arrival
     :schedules {:some-arrival-schedule the-schedule}})

;;Now when we process arrivals, we break up the op into two phases...
;;old...
#_(defn process-arrivals
    "The arrivals system processes batches of entities and schedules more
   arrival updates. batch->entities should be a function that maps a batch, {:t
   long :n long} -> [entity*]"
    ([batch->entities ctx]
     (if-let [arrivals? (seq (sim/get-updates :arrival
                                 (sim/current-time ctx) ctx))]
       (let [_      (debug "[<<<<<<Processing Arrivals!>>>>>>]")
             arr    (store/get-entity ctx :arrival) ;;known entity arrivals...
             {:keys [pending arrival-fn next-batch]}    arr
             new-entities (batch->entities pending)
             new-batch    (next-batch (:t pending) ctx)]
         (->> ctx
              (services/handle-arrivals (:t pending) new-entities)
              (services/schedule-arrivals new-batch)))
       (do (spork.ai.core/debug "No arrivals!")
           ctx)))
    ([ctx] (process-arrivals (or (store/gete ctx :parameters :batch->entities)
                                 services/batch->entities) ctx)))

(defprotocol IBatchProvider
  (peek-batch [o])
  (pop-batch  [o]))

;;do we compute the arrival time first?
;;If we have identical arrival times (dt = 0),
;;we need to consolidate the batches....
;;implication of multiple dt=0, is that
;;batches are arriving concurrently.
;;When we hit a dt <> 0, then we have future
;;batch...
(defn ->random-batch
  ([f size-f]
   (-> (loop [dt  (f)
              acc (size-f)]
         (if (pos? dt)
           {:n acc :dt dt}
           (recur (f) (+ acc (size-f)))))))
  ([f]   (-> (loop [dt  (f)
                    acc 1]
               (if (pos? dt)
                 {:n acc :dt dt}
                 (recur (f) (inc acc)))))))
;;given a start-time, and an extant delay, dt,
;;associates the start-time with t in the map.
(defn offset [t {:keys [dt] :as b}]
  (assoc b :t (+ dt t )))

(defn variable [n]
  (cond (fn? n) n
        (number? n) (fn [] n)
        :else (ex-info "requires fn or numeric constant!" {})))

(defn next-batch
  "Given a start time t, and an interarrival time
   function f::nil->number, generates a map of
   {:n arrival-count :t next-arrival-time} where t
   is computed by sampling from f, such that the
   interarrival time is non-zero.  Zero-values
   are aggregated into the batch via incrementing
   n, accounting for concurrent arrivals (i.e. batches)."
  ([t interarrival]
   (->> (->random-batch (variable interarrival))
        (offset t )))
  ;;complecting...I think batch->entities should handle behavior...
  ([t f b] (assoc (next-batch t f) :behavior b))
  ([t interarrival size b]
   (as-> (->random-batch (variable interarrival) (variable size)) it
         (offset t it)
         (assoc it :behavior b))))

;;this works for persistent, immutable batches.
(defn gen-batch
  ([t {:keys [tstart tstop interarrival size capacity beh]}]
   (when (and  (>= t tstart)
               (or (not tstop) (< t tstop)))
     (let [b (next-batch t interarrival size beh)]
       (if-not capacity b
               (let [n (:n b)
                     delta (- capacity n)]
                 (if (pos? delta)
                   b
                   (assoc b :n capacity)))))))
  ([b] (gen-batch 0 b)))


;;currently, random batches are maps.
;;constant batches are sequences of primitive entities.
(defn peek-batch-map [m]
  (:pending m))

(defn in-bounds? [m b]
  (or (not (:tstop m))
      (<= (:t b) (:tstop m))))

(defn push-batch-map [m b]
  (let [newm (assoc m :pending b)]
    (if-not (:capacity m)
      newm
      (update newm :capacity - (:n b)))))

(defn pop-batch-map [m]
  (let [b (peek-batch m)
        new-batch  (gen-batch (:t b) m)]
   (if (in-bounds? m new-batch)
     (push-batch-map m new-batch)
     (assoc m :pending nil))))


;;We can compute a successive batch from a seed
(defrecord batchseed [tstart tstop interarrival size  capacity behavior]
  IBatchProvider
  (peek-batch [o] (peek-batch-map o))
  (pop-batch  [o] (pop-batch-map o)))

(defn ->random-schedule
  [& {:keys [tstart tstop interarrival size  capacity behavior]
      :or {tstart 0 size 1}}]
  (let [b (->batchseed tstart
                       tstop
                       interarrival
                       size
                       capacity
                       behavior)]
    (assoc b :pending (gen-batch tstart b))))

;;defines a sequence-based schedule of entity arrivals
(defn ->known-schedule [behavior xs]
  {:pending  xs
   :behavior behavior})

(extend-protocol IBatchProvider
  clojure.lang.PersistentArrayMap
  (peek-batch [o] (first (:pending  o)))
  (pop-batch  [o] (update o :pending rest)))

;;useful for testing...
(defn finite-schedule [bt]
  (->> bt
       (iterate pop-batch)
       (take-while :pending)
       (map :pending)))

;;in services..
;;If we have active-schedules, they have batches.
;;we need to pop the next batch from the each schedule.
;;This will produce a new schedule, and in turn,
;;request.  compute the entity batches, new updates, and updated
;;schedules.
(defn pop-batches
  "Given a context, and entity realization function, and selected schedule
  entities, computes a map of {:keys [entities updates schedules]}"
  [#_ctx batch->entities schedules]
  ;;we need to traverse each schedule
  (reduce (fn [{:keys [entities updates schedules ctx]} sched]
            (let [b                (peek-batch sched) ;;get the next-batch.
                  new-sched        (pop-batch  sched)] ;;get the next-schedule (if any)
              {:entities  (into entities (batch->entities b)) ;;collect entities
               :updates   (if-let [t (:t (peek-batch new-sched))]
                            (conj updates t) updates)
               :schedules (conj schedules new-sched)
               #_ :ctx #_ ctx}))
          {:entities  [] :updates   #{} :schedules [] #_:ctx #_ctx} schedules))

(comment 
;;in services...
(defn active-schedules [ctx t schedule-names]
  (store/select-entities ctx
     :from [:schedule]
     :where (fn [e] (= (:t e) t))))

;;new....
(defn process-arrivals
  "The arrivals system processes batches of entities and schedules more arrival
   updates.  batch->entities should be a function that maps a batch, 
   {:t long :n long} -> [entity*]"
  ([batch->entities ctx]
   (if-let [arrivals? (seq (sim/get-updates :arrival (sim/current-time ctx) ctx))]
     (let [_      (debug "[<<<<<<Processing Arrivals!>>>>>>]")
           ;;look up the schedules in arrivals for entities.
           schedules (services/active-schedules ctx
                        (sim/current-time ctx)
                        (store/gete ctx :arrival :schedules))
           ;;compute the entity batches, new updates, and updated schedules.
           {:keys [entities updates schedules]}
               (services/pop-batches ctx batch->entities schedules)]
       (as-> newctx ctx
         (request-updates :arrival updates newctx) ;merge arrival  updates.
         (merge-entities newctx schedules)         ;merge new schedules.
         ;;process the entities from the batches in the new context.
         (services/handle-arrivals newctx t new-entities entities)))
     (do (spork.ai.core/debug "No arrivals!")
         ctx)))
  ([ctx] (process-arrivals (or (store/gete ctx :parameters :batch->entities)
                               services/batch->entities) ctx)))
)


















