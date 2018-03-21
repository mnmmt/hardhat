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
  (atom {:display {:screen :mods
                   :module nil
                   :channel 0}
         :playing nil
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

; check how we were called (dev or prod)
(def in-lumo (>= (.indexOf (get process/argv 0) "lumo") 0))
(def args (.slice process/argv (if in-lumo 3 2)))

; terminal codes to clear the screen
(def clear-screen (.toString (js/Buffer. #js [27 91 72 27 91 50 74])))

; triage tty output from xmp
(def re-line #"^.*([A-Z0-9]{2})\/([A-Z0-9]{2})\] Chn\[")
(def re-bpm #"Speed\[(.*?)\] BPM\[(.*?)\]")

; find shell command args to discover module files
(def find-args "-maxdepth 3 -type f \\( -iname *.xm -o -iname *.it -o -iname *.s3m -o -iname *.mod -o -iname *.med -o -iname *.oct -o -iname *.ahx \\)")

; function to find module files
(defn find-mod-files [dirs]
  (if (> (count dirs) 0)
    (let [find-cmd (str "find '" (clojure.string/join "' '" dirs) "' " find-args)]
      (to-array (remove #(= % "") (.split (.toString (child_process/execSync find-cmd)) "\n"))))
    #js []))

; ***** display handling ***** ;

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
  (let [pos (.indexOf (to-array modules) module)
        ;modules (concat ["---"] modules)
        modules (concat modules modules)
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
    :mods (lcd-track-list (-> state :modules) (-> state :display :module))
    :play (lcd-channel-list (-> state :display :channel)))
  (print state))

; if the ui atom changes, update the ui
(add-watch app-state :ui-changes
            (fn [k a old-state new-state]
              (update-ui! new-state)))

; ***** input handling ***** ;

; key handler functions
(defn scroll-fn [dir-fn state]
  (let [modules (@state :modules)
        module (-> @state :display :module)
        index (max (.indexOf modules module) 0)]
    (swap! state assoc-in [:display :module] (get modules (mod (dir-fn index) (count modules))))))

; key handler map
(def keymap
  {:mods {:right
          (fn [state])
          :left
          (fn [state])
          :up
          (partial scroll-fn dec)
          :down
          (partial scroll-fn inc)
          :select
          (fn [state])}
   :play { }})

; handle keys
(defn press-key [k state]
  ((get-in keymap [(get-in @state [:display :screen]) k]) state))

; set up input
(if lcd
  (do)
  (do
    (.setRawMode process/stdin true)
    (.resume process/stdin)
    (.setEncoding process/stdin "utf8")
    (.on process/stdin "data"
         (fn [k]
           (let [v (.toString (js/Buffer. k) "hex")]
             (case v
               "1b5b44" (press-key :left app-state)
               "1b5b43" (press-key :right app-state)
               "1b5b41" (press-key :up app-state)
               "1b5b42" (press-key :down app-state)
               "20" (press-key :select app-state)
               "0d" (press-key :select app-state)
               "1b" (process/exit)
               "03" (process/exit)
               (js/console.log "key" v)))))))

; ***** mod player control ***** ;

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

; ***** init ***** ;

(swap! app-state
       (fn [old-state]
         (let [mod-files (find-mod-files args)]
         (-> old-state
             (assoc :modules mod-files)
             (assoc-in [:display :module] (first mod-files))))))
