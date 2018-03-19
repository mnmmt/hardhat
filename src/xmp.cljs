#!./node_modules/.bin/lumo

(ns xmp.core
  (:require
    [path]
    [process]
    [clojure.string]
    [child_process]
    [node-pty]))

(enable-console-print!)

(def app-state
  (atom {:display {:screen nil :module nil :channel 0}
         :modules []
         :bpm 180}))

; TODO:
;  * sync signal
;  * LCD UI
;  * physical build
;  * TC - automount USB

; UI design:
;
; > module 1.xm
;   module 2.it
;
; > all on
;   ch 1  on
;
; > ch 3  on
;   ch 4  off

(def gpio (try (.-Gpio (js/require "onoff"))
            (catch :default e nil)))

(def lcd (try (let [plate (.-plate (js/require "adafruit-i2c-lcd"))]
                (plate. 1 0x20))
              (catch :default e nil)))

(when lcd
  ; turn on sainsmart 1602 I2C backlight
  (.sendBytes lcd 0 0x1F))

(console.log "xmp.cljs")

; terminal codes to clear the screen
(def clear-screen (.toString (js/Buffer. #js [27 91 72 27 91 50 74])))

(def re-line #"^.*([A-Z0-9]{2})\/([A-Z0-9]{2})\] Chn\[")
(def re-bpm #"Speed\[(.*?)\] BPM\[(.*?)\]")

(def find-args "-maxdepth 3 -type f \\( -iname *.xm -o -iname *.it -o -iname *.s3m -o -iname *.mod -o -iname *.med -o -iname *.oct -o -iname *.ahx \\)")

(def in-lumo (>= (.indexOf (get process/argv 0) "lumo") 0))
(def args (.slice process/argv (if in-lumo 3 2)))

(defn find-mod-files [dirs]
  (if (> (count dirs) 0)
    (let [find-cmd (str "find '" (clojure.string/join "' '" dirs) "' " find-args)]
      (.split (.toString (child_process/execSync find-cmd)) "\n"))
    []))

(defn lcd-print [messages]
  (let [txt (clojure.string/join "\n" messages)]
    (if lcd
      (do
        (.clear lcd)
        (.message lcd txt))
      (do
        (print clear-screen)
        (print txt)))))

(defn lcd-track-list [modules module]
  (let [pos (.indexOf modules module)
        mods (->> modules
                  (split-at pos)
                  (second)
                  (split-at 2)
                  (first)
                  (map path/basename))
        mods [(str "> " (first mods))
              (str "  " (second mods))]]
    (lcd-print mods)))

(defn lcd-channel-list [channel]
  (lcd-print (map #(str "ch " %)
                  (->> (range 8)
                       (split-at channel) 
                       (second) 
                       (split-at 2) 
                       (first)))))

(defn update-ui! [state]
  (case (-> state :display :screen)
    nil (lcd-track-list (-> state :modules) (-> state :display :module))
    :track (lcd-channel-list (-> state :display :channel)))
  (print state))

; if the ui atom changes, update the ui
(add-watch app-state :ui-changes
            (fn [k a old-state new-state]
              (update-ui! new-state)))

(swap! app-state assoc :modules (find-mod-files args))

; set up sync pin out
(when gpio
  (def sync-pin (gpio. 17 "out")))

(let [p (node-pty/spawn "./xmp-wrap" #js ["-l" "./test.it"] process/env)]
  (.on p "data"
       (fn [data]
         (let [[match-bpm subticks rate] (re-find re-bpm data)
               [match-tick tick len] (re-find re-line data)]
           ;(println "---> LINE:" data)
           (if match-bpm (println "---> BPM:" subticks (js/parseInt rate 16)))
           (when match-tick
             (println "---> Match:" tick len)
             ; send sync signal out
             (when sync-pin
               (.write sync-pin 1)
               (js/setTimeout (fn [] (.write sync-pin 0)) 3)))))))

; toggle channel
; (p.write "1")

; unmute all
; (p.write "!")

; toggle pause
; (p.write " ")

