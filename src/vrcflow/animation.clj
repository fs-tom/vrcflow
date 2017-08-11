(ns vrcflow.animation
  (:require [piccolotest
             [sample :as picc]
             [board :as board]
             [gis :as gis]]
            [spork.graphics2d.image]
            [spork.util [io :as io]]))


(def coords
  {"AWC"
   (apply concat
          [
   (for [y [528
            396
            300
            230
            175]]
     [25 y])   
   (for [y [528
            396
            330
            198]]
     [100, y])
   (for [y [594
            528
            462
            396
            330
            264
            132]]
     [180, y])])
   "WAIT" [[587, 462]]
   "MFRC" [[264 528]
           [352 548]
           [469 548]
           [300 264]]
   "HPO" [[587, 548]]
   "CLASS" [[587 264]]
   "CHPLN SVCS"  [[704 548]]
   "ASAP" [[704 462]
           [704 396]]
   "CSF2" 
   [[382 289]
    [469 284]]
   "NUTR SVCS"
   [[382 198]
    [469 198]]
   "APHN"
   (apply concat
          [(for [y [528
                   462
                   396
                   330
                   264
                   198
                   162]]
            [881 y])
          [[770 630]
           [850 630]
           [750 300]
           [750 280]
           [770 462]
           [770 396]]])
   "TK" [[352 162]
         [352 96]
         [352 30]]})

(def lbls
  {"MFRC" "Military And Family Readiness Center",
   "AWC" "Army Wellness  Center",
   "TK" "Teaching Kitchen",
   "NUTR SVCS" "Nutritional Medicine",
   "VRC" "VRC Reception Area",
   "CSF2" "Comprehensive Soldier and Family Fitness Program",
   "CHPLN SVCS" "Chaplain Services",
   "WAIT" "VRC Waiting Area",
   "ASAP" "Army Substance Abuse Program",
   "APHN" "Army Public Health Nursing",
   "HPO" "Health Promotion Operations"})

(def places
  (for [[plc coords] coords
        [x y] coords]
    {:x x :y y :Location (lbls plc)
     :id plc}))            


(def vrc-map
  (picc/->image (spork.graphics2d.image/read-image (io/get-resource "vrclayout.png"))))

(defn composite-map
  []
  (picc/as-node
   [[(picc/->grid-lines vrc-map)
      vrc-map]]))

(defn ->points [nd]
  (let [bnds (picc/get-full-bounds nd)
        w (.getWidth bnds)
        h (.getHeight bnds)]
    (picc/->layer       
     (for [x (range 0 w (/ w 10.0))
           y (range 0 h (/ h 10.0))]
       (picc/add-child (picc/->rect :red x y 10 10 {:x x :y y})
                       (picc/->translate x y (picc/->text (str [(long x) (long y)]))))))))

(defn coords->points [xs]
  (picc/->layer       
   (for [{:keys [x y]}  xs]
     (picc/add-child (picc/->rect :red x y 10 10 {:x x :y y})
                     (picc/->translate x y (picc/->text (str [(long x) (long y)])))))))

        
(defn add-points
  ([nd] (add-points nd (->points nd)))
  ([nd pts]
    (picc/add-child nd pts)))
            

(defn empty-map [& {:keys [ arcs?] :or { arcs? true}}]                
  (gis/make-map (composite-map)
                (fn coords* [nds nd]
                  (if-let [nd (gis/get-node nds nd)]
                    (if (vector? nd) nd
                        (gis/default-coords nds nd))
                    (gis/default-coords nds nd)))
                :places (into {} (map (juxt :id (juxt :x :y)) places))
                :arcs? arcs?))


(defn ->client []
  (picc/->filled-rect :blue 0 0 10 10))

;;working on client api...
#_(defn add-client [m nm]
  (gis/add-token ))

(comment ;testing
  (defn ->client []
    (picc/->filled-rect :blue 0 0 10 10))
  (def client (picc/->filled-rect :blue 0 0 10 10))
  (def e (-> (empty-map) (gis/add-token :client client)))
  (def clck (atom 0))
  (picc/render! e)
  ;;THere's a problem with gis/send-to, in that the
  ;;call to picc/do-scene always returns nil, so we end up
  ;;closing the response channel too early.
  (let [places (keys (gis/places e))]
    (dotimes [i 10]
      (let [target (rand-nth places)
            _ (println target)
            res (gis/send-to clck e :client target)
            p   (promise)
            _   (clojure.core.async/go
                  (let [x (clojure.core.async/<! res)]
                      (deliver p x)))]
        (while (not (realized? p))
          (do  (swap! clck inc)
               (Thread/sleep 16)
               ))
        )))
                                         ;)
            

  )
