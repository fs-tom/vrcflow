;;We need a consistent way to formalize arrivals and entity batches, which may
;;be pre-determined (ala an arrivals file or sequence) or
;;stochastically-generated (via variable interarrival and batch-size fns.)
(ns vrcflow.batch
  (:require [spork.entitysystem.store :as store]))

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
;;the batch-size of entities to-be-generated.  Contrast this with the
;;deterministic representation {:keys [batch t behavior]}, where
;;the batch is already computed.

;;Perhaps a better representation is {:keys [entities n t behavior]}
;;If entities already exists, then we batch->entities returns them
;;directly.   If-not, then we use n to randomly generate a sequence
;;of entities.


;;On the arrivals side, there are substantial differences:
;;During initial processing/context creation, the
;;arrivals are processed differently based on
;;if they are a sequence of batches, or an actual batch.
;;A sequence of batches goes through schedule-multiple-batches,
;;and currently bypasses the arrivals entity....
;;  All entities are manually scheduled for arrival,
;;  update requests, and added to the entity-store.
;;In the batch case, we assoc the batch onto
;;the :pending component of the arrivals entity,
;;and request an update at the time of the batch for
;;the arrivals entity.  This hooks into the arrivals
;;system and lets us process normally...

;;Can we unify these concepts, hook into the arrivals
;;system, and schedule entities for batch arrival
;;on-demand vs. a-priori?

;;a) unify batch representation, avoid custom batch->entities
;;If we unify the batch representation to include...
;;an :entities key (currently :batch) then we can
;;fold in the entities->batch function to just
;;pre-computed entities vs. generating, otherwise
;;rely on the :n key (should be batch-size) to
;;stochastically generate entities from the current
;;batch, and derive the next-batch.

;;b) allow next-batch to account for pending
;;pre-computed entities.  We'd like to have
;;next-batch play nice with the arrivals
;;entity, so that the first batch
;;is stored in the :pending component.
;;We'd always like to know what's scheduled
;;by looking at the :pending component
;;of arrivals.

;;In the case of recurring stochastic
;;batches, we have a pending batch, from
;;which future batches will be computed.
;;We may want to examine this to allow
;;expressing things like finite-batches
;;(generate until some condition is met)

;;In the case of deterministic batches,
;;we want to continue drawing from the
;;remaining batches, scheduling them as
;;the next pending batch.

;;One option is to treat :pending as
;;polymorphic.  If it's a stochastic
;;batch, we create entities and - maybe
;;- compute a next-batch.

;;sidebar - stochastic batches should
;;have an explicit stopping criteria...

;;If it's a sequential batch, we
;;want to draw the next batch via
;;first.  Process the entities
;;via batch->entities, then
;;update the pending arrivals
;;by popping the first batch.

;;So, next-batch should be
;;able to operate on the
;;arrivals entity.

;;These are both views of a batch-sequence.
;;In the stochastic case, we produce
;;a sequence of batches by iterating
;;on the current batch, generating the next
;;via applying a transition function to the
;;current pending batch (next-batch).

;;In the deterministic case, we do something
;;similar, our transition function is just
;;next....

;;Maybe it makes sense to have a :pending and
;;a :scheduled component.

;;The state-xn function is simpler.
;;:pending is always the next operable
;;batch of entities.
;;:scheduled is the rest of the sequence.
;;to get the next pending, we get the first
;;of scheduled.
;;To get the next scheduled, we take the rest
;;of scheduled (or pop).

;;While batch-time = current-time, schedule
;;arrivals for the batch.

;;This lets us abstract better...

;;What's a decent api?
;;request-arrival::batch -> ctx -> ctx

;;Do we have a different API for generative arrivals.
;;generate-arrival::initial-batch -> ctx -> ctx

;;Or do we provide a sequence of generated arrivals
;;and live with that?

;;pull-based
;;schedule-arrivals::batch seq|chan -> ctx ->ctx

;;given a sequence (either realized or
;;generated lazily) of batches
;;{:keys [t n? entities? behavior]}
;;registers the first batch as :pending
;;batch, storing the remainder of the sequence
;;as :remaining


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

(defn next-batch   [t ctx]
  (services/next-batch t
                       next-arrival next-size default-behavior))

(defn ->stochastic-batches
  ([size next-batch]
   (next-batch (sim/get-time ctx) ctx)))





