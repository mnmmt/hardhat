#!./node_modules/.bin/lumo

(ns xmp.core
  (:require
    [path]
    [process]
    [clojure.string]
    [child_process]
    [node-pty]
    [cljs.core.async :refer [chan put! <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(def default-channels [1 1 1 1 1 1 1])

(def edit-menu [:play-state :tick-freq :unmute-all])

(def app-state
  (atom {:display {:screen :mods
                   :module nil
                   :edit-line 0}
         :player {:playing nil
                  :play-state "stop"
                  :tick-freq 4
                  :bpm 180
                  :channels default-channels}
         :modules []
         :sync {:last-row 0}}))

(def player-chan (chan))

; TODO:
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

; set up sync pin out
(def sync-pin (when gpio (gpio. 17 "out")))

(when lcd
  ; turn on sainsmart 1602 I2C backlight
  (.sendBytes lcd 0 0x1F))

(console.log "xmp.cljs")

; check how we were called (dev or prod)
(def in-lumo (>= (.indexOf (get process/argv 0) "lumo") 0))
(def args (.slice process/argv (if in-lumo (inc (.indexOf process/argv "src/xmp.cljs")) 2)))

; terminal codes to clear the screen
(def clear-screen (.toString (js/Buffer. #js [27 91 72 27 91 50 74])))

; find shell command args to discover module files
(def find-args "-maxdepth 3 -type f \\( -iname \\*.xm -o -iname \\*.it -o -iname \\*.s3m -o -iname \\*.mod -o -iname \\*.med -o -iname \\*.oct -o -iname \\*.ahx \\)")

; function to find module files
(defn find-mod-files [dirs]
  (if (> (count dirs) 0)
    (let [find-cmd (str "find '" (clojure.string/join "' '" dirs) "' " find-args)]
      (to-array (remove #(= % "") (.split (.toString (child_process/execSync find-cmd)) "\n"))))
    #js []))

; check if keys have changed
(defn changed? [a b ks]
  (not= (map a ks)
        (map b ks)))

; ***** display handling ***** ;

(defn play-state-toggle [play-state]
  (if (= play-state "play") "stop" "play"))

(defn lcd-print [messages]
  (let [txt (clojure.string/join "\n" messages)]
    (if lcd
      (do
        (.clear lcd)
        (.message lcd txt))
      (do
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

(defn lcd-edit-list [edit-line {:keys [play-state tick-freq channels]}]
  (let [channel-list (if (= play-state "play")
                       (range (count default-channels))
                       [])
        lines (->> channel-list
                   (map #(str "[" (if (get channels %) "X" " ")  "] ch " (inc %)))
                   (concat [(play-state-toggle play-state) (str "tick: " tick-freq)]))
        lines (concat lines lines)
        lines (->> lines
                   (split-at (or edit-line 0))
                   (second)
                   (split-at 2)
                   (first)) 
        ; TODO left-pad first to add BPM
        lines [(str "> " (first lines))
               (str "  " (second lines))]]
    (lcd-print lines)))

(defn update-ui! [state]
  (print "update-ui! state:")
  (print state)
  (case (-> state :display :screen)
    :mods (lcd-track-list (-> state :modules) (-> state :display :module))
    :play (lcd-edit-list (-> state :display :edit-line) (state :player))))

; ***** input handling ***** ;

(defn cycle-values [values old-value]
  (let [pos (.indexOf (clj->js values) (clj->js old-value))]
    (get values (mod (inc pos) (count values)))))

; key handler functions
(defn scroll-mods-fn [dir-fn state]
  (let [modules (@state :modules)
        module (-> @state :display :module)
        index (max (.indexOf modules module) 0)]
    (swap! state assoc-in [:display :module] (get modules (mod (dir-fn index) (count modules))))))

(defn get-channels [state]
  (if (= (-> state :player :play-state) "play")
    (-> state :player :channels)
    []))

(defn scroll-edit-fn [dir-fn state]
  (let [channels (get-channels @state)]
    (swap! state update-in [:display :edit-line] (fn [edit-line] (mod (dir-fn edit-line) (+ (count channels) 2))))))

(defn toggle-screen! [state]
  (swap! state update-in [:display :screen] (partial cycle-values [:play :mods])))

(defn select-mod! [state]
  (swap! state
         #(-> %
              (assoc-in [:player :playing] (-> % :display :module))
              (assoc-in [:player :channels] default-channels)
              (assoc-in [:display :screen] :play))))

(defn select-player-setting! [state]
  (let [max-line (count (+ (get-channels @state) 2))
        line (min (or (-> @state :display :edit-line) 0) (dec max-line))]
    (cond
      (= line 0) (swap! state
                        #(-> %
                             (update-in [:player :play-state] (partial cycle-values ["stop" "play"]))
                             (assoc-in [:player :channels] default-channels)))
      (= line 1) (swap! state update-in [:player :tick-freq] (partial cycle-values [1 2 4 8 3 6]))
      (and (> line 1) (< line max-line)) (swap! state update-in [:player :channels (- line 2)] not)
      :else (print "line: " line))))

; key handler map
(def keymap
  {:mods {:right
          toggle-screen!
          :left
          toggle-screen!
          :up
          (partial scroll-mods-fn dec)
          :down
          (partial scroll-mods-fn inc)
          :select
          select-mod!}
   :play {:right
          toggle-screen!
          :left
          toggle-screen!
          :up
          (partial scroll-edit-fn dec)
          :down
          (partial scroll-edit-fn inc)
          :select
          select-player-setting!}})

; handle keys
(defn press-key [state k]
  ((get-in keymap [(get-in @state [:display :screen]) k]) state))

(def button-map
  {0x01 :select
   0x02 :right
   0x04 :down
   0x08 :up
   0x10 :left})

; set up input
(if lcd
  ; using LCD button interface on device
  (.on lcd "button_down"
       (fn [button]
         (press-key app-state (get button-map button))))
  ; using console tty for development
  (do
    (.setRawMode process/stdin true)
    (.resume process/stdin)
    (.setEncoding process/stdin "utf8")
    (.on process/stdin "data"
         (fn [k]
           (let [v (.toString (js/Buffer. k) "hex")]
             (case v
               "1b5b44" (press-key app-state :left)
               "1b5b43" (press-key app-state :right)
               "1b5b41" (press-key app-state :up)
               "1b5b42" (press-key app-state :down)
               "20" (press-key app-state :select)
               "0d" (press-key app-state :select)
               "1b" (process/exit)
               "03" (process/exit)
               (js/console.log "key" v)))))))

; ***** mod player control ***** ;

(defn send-pulse [last-tick]
  (when sync-pin
    (.writeSync sync-pin 1)
    (js/setTimeout (fn [] (.writeSync sync-pin 0)) 4)) )

(defn got-player-data! [state data]
  (let [lines (.split (.toString data) "\r\n")]
    (doseq [line lines]
      (let [s (try (JSON.parse line) (catch :default e nil))]
        (when s
          (let [row (.-row s)
                last-row (get-in @state [:sync :last-row])
                time-hw-ms (.-time_hw s)
                time-alsa-delay-ms (.-time_alsa_delay s)
                bpm (.-bpm s)]
            (when (and (= (mod row 4) 0)
                       (not= row last-row))
              (let [next-tick-ms (- time-alsa-delay-ms (- (.getTime (js/Date.)) time-hw-ms))]
                (js/setTimeout send-pulse (max 0 next-tick-ms))
                (swap! state #(-> %
                                  (assoc-in [:sync :last-row] row)
                                  (assoc-in [:player :bpm] bpm)))))))))))

; toggle channel
; (p.write "1")

; unmute all
; (p.write "!")

; toggle pause
; (p.write " ")

; ***** init ***** ;

(defn main []
  ; start the xmp manager loop
  (go
    (loop [player nil]
      ; kill the old xmp session
      (let [module-file (<! player-chan)]
        (when player (.kill player))
        (if (= module-file :stop)
          (recur nil)
          (let [new-player (node-pty/spawn "./xmp-wrap" (clj->js ["-l" module-file]) #js {:env process/env})]
            (.on new-player "data" (partial got-player-data! app-state))
            (recur new-player))))))

  ; what to do when mutation happens
  (add-watch app-state
             :ui-changes
             (fn [k a old-state new-state]
               ; when active module changes send to xmp manager loop
               (when (changed? (old-state :player) (new-state :player) [:playing :play-state])
                 (put! player-chan
                       (if (= (-> new-state :player :play-state) "play")
                         (-> new-state :player :playing)
                         :stop)))
               ; update the user interface
               (when (changed? old-state new-state [:player :display])
                 (update-ui! new-state))))

  ; set the initial state after loading in module list
  (swap! app-state
         (fn [old-state]
           (let [mod-files (find-mod-files args)]
             (-> old-state
                 (assoc :modules mod-files)
                 (assoc-in [:display :module] (first mod-files)))))))

; avoid missing goog.global.setTimeout
; https://dev.clojure.org/jira/browse/ASYNC-110
(js/setTimeout main 0)
