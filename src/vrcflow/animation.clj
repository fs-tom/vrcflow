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
            270
            198
            125]]
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
           [469 548]]
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
           [728 330]
           [704 264]]])
   "TK" [[352 132]
         [352 66]
         [352 0]]})

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

(def places
  {:y 602.0, :x 160.0, :Location "", :id "AWC"})

(def vrc-map
  (picc/->image (spork.graphics2d.image/read-image (io/get-resource "vrclayout.png"))))

(defn composite-map
  []
  (picc/as-node
   [[(picc/->grid-lines vrc-map)
      vrc-map]]))

(defn add-points [nd]
  (let [bnds (picc/get-full-bounds nd)
        w (.getWidth bnds)
        h (.getHeight bnds)
        l (picc/->layer       
           (for [x (range 0 w (/ w 10.0))
                 y (range 0 h (/ h 10.0))]
             (picc/add-child (picc/->rect :red x y 10 10 {:x x :y y})
                             (picc/->translate x y (picc/->text (str [(long x) (long y)]))))))]
    (picc/add-child nd l)))
            

(defn empty-map [& {:keys [ arcs?] :or { arcs? true}}]
  (let [the-map (composite-map)
        places  (reduce-kv (fn [acc k v]
                             (assoc acc (:id k) v)) {} (picc/node-map the-map))
        additional-places (clojure.set/difference (set (keys maps/all-locs))
                                                  (set (keys places)))
        final-places (into places
                           (filter (comp additional-places  first)) (seq maps/all-locs))
         ]                 
    (gis/make-map the-map coords* :places final-places :arcs? arcs?)))

