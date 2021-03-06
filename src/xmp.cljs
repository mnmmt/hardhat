#!./node_modules/.bin/lumo

(ns xmp.core
  (:require
    [path]
    [dgram]
    [process]
    [clojure.string]
    [child_process]
    [node-pty]
    [minimist]
    [cljs.core.async :refer [chan put! <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

; ***** state ***** ;

(def default-channels [1 1 1 1
                       1 1 1 1
                       1 1 1 1
                       1 1 1])

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
         :sync {:last-row 0}
         :player-chan (chan)}))

(console.log "xmp.cljs")

; ***** args ***** ;

; check how we were called (dev or prod)
(def in-lumo (>= (.indexOf (get process/argv 0) "lumo") 0))
(def argv (js->clj (minimist (.slice process/argv (if in-lumo (inc (.indexOf process/argv "src/xmp.cljs")) 2)))))

(def modfile-dirs (get argv "_"))
(def ui-host (get argv "u"))

; ***** interfaces ***** ;

(def gpio (try (.-Gpio (js/require "onoff"))
            (catch :default e nil)))

; set up sync pin out
(def sync-pin (when gpio (gpio. 22 "out")))

(def lcd (try (let [plate (.-plate (js/require "adafruit-i2c-lcd"))]
                (plate. 1 0x20))
              (catch :default e nil)))

(when lcd
  ; turn on sainsmart 1602 I2C backlight
  (.sendBytes lcd 0 0x1F))

(def ui-socket
  ; set up our connection to the UI socket
  (when ui-host
    (let [dgram (.createSocket dgram "udp4")]
      (.send dgram "     loading.     " 3323 ui-host)
      dgram)))

(def screen-size
  (cond
    ui-socket
    [16 4]

    lcd
    [14 2]

    :else
    [16 4]))

; ***** mod files ***** ;

; find shell command args to discover module files
(def find-args "-maxdepth 5 -type f \\( -iname \\*.xm -o -iname \\*.it -o -iname \\*.s3m -o -iname \\*.mod -o -iname \\*.med -o -iname \\*.oct -o -iname \\*.ahx \\)")

; function to find module files
(defn find-mod-files [dirs]
  (if (> (count dirs) 0)
    (let [find-cmd (str "find '" (clojure.string/join "' '" dirs) "' " find-args)]
      (to-array (remove #(= % "") (.split (.toString (child_process/execSync find-cmd)) "\n"))))
    #js []))

; ***** display handling ***** ;

; terminal codes to clear the screen
(def clear-screen (.toString (js/Buffer. #js [27 91 72 27 91 50 74])))

(defn play-state-toggle [play-state]
  (if (= play-state "play") "stop" "play"))

(defn lcd-print [messages]
  (let [txt (clojure.string/join "\n" messages)]
    (cond
      ui-socket
      (.send ui-socket txt 3323 ui-host)

      lcd
      (do
        (.clear lcd)
        (.message lcd txt))

      :else
      (print txt))))

(defn lcd-track-list [modules module]
  (let [pos (.indexOf (to-array modules) module)
        ;modules (concat ["---"] modules)
        modules (if (> (count modules) 1) (concat modules modules) modules)
        mods (->> modules
                  (split-at pos)
                  (second)
                  (split-at (second screen-size))
                  (first)
                  (map path/basename)
                  (map #(.substr % 0 (first screen-size))))
        mods (concat [(str "> " (first mods))]
                     (map #(str "  " %) (rest (take (inc (second screen-size)) mods))))]
    (lcd-print mods)))

(defn lcd-edit-list [edit-line {:keys [play-state tick-freq channels bpm]}]
  (let [lines (->> (range (count channels))
                   (map #(str "[" (if (get channels %) "X" " ")  "] c" (.toUpperCase (.toString (inc %) 16))))
                   (concat [(play-state-toggle play-state) (str "tick: " tick-freq) "unmute"]))
        lines (concat lines lines)
        lines (->> lines
                   (split-at (or edit-line 0))
                   (second)
                   (split-at (second screen-size))
                   (first)) 
        first-line (first lines)
        first-line (str first-line
                        (apply str (for [x (range (- (- (first screen-size) 6) (count first-line)))] " "))
                        (if (= play-state "play") bpm "---")
                        "bpm")
        lines (concat [(str "  " (clojure.string/join "" (map #(str (if (get channels %) (.toUpperCase (.toString (inc %) 16)) " ")) (range (count channels)))))
                       (str "> " first-line)]
                      (map #(str "  " %) (rest (take (second screen-size) lines))))]
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

(defn scroll-edit-fn [dir-fn state]
  (let [channels (-> @state :player :channels)]
    (swap! state update-in [:display :edit-line] (fn [edit-line] (mod (dir-fn edit-line) (+ (count channels) (count edit-menu)))))))

(defn toggle-screen! [state]
  (swap! state update-in [:display :screen] (partial cycle-values [:play :mods])))

(defn select-mod! [state]
  (swap! state
         #(-> %
              (assoc-in [:player :playing] (-> % :display :module))
              (assoc-in [:display :screen] :play))))

(defn toggle-channel! [channel-index state]
  (swap! state update-in [:player :channels channel-index] not)
  (put! (@state :player-chan) [:unmute channel-index]))

(defn select-player-setting! [state]
  (let [line (or (-> @state :display :edit-line) 0)
        menu-length (count edit-menu)
        channel-count (count (-> @state :player :channels))
        selection (get edit-menu line)
        player-chan (@state :player-chan)
        channel-index (- line menu-length)]
    (cond
      (= selection :play-state) (swap! state
                                       #(-> %
                                            (update-in [:player :play-state] (partial cycle-values ["stop" "play"]))))
      (= selection :tick-freq) (swap! state update-in [:player :tick-freq] (partial cycle-values [1 2 4 8 3 6]))
      (= selection :unmute-all) (do (swap! state assoc-in [:player :channels] default-channels)
                                    (put! player-chan [:unmute :all]))
      (and (>= channel-index 0) (< channel-index channel-count)) (toggle-channel! channel-index state)
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
  (let [key-fn (get-in keymap [(get-in @state [:display :screen]) k])]
    (if key-fn
      (key-fn state)
      (print "No such key-fn:" k))))

(def lcd-button-map
  {0x01 :select
   0x02 :right
   0x04 :down
   0x08 :up
   0x10 :left})

(def socket-button-map
  {0 :up
   1 :down
   2 :left
   3 :right
   4 :select
   5 :select
   6 :select
   7 :select
   8 :select})

; check if keys have changed
(defn changed? [a b ks]
  (not= (map a ks)
        (map b ks)))

; set up input
(cond
  ui-socket
  (.on ui-socket "message"
       (fn [message remote]
         (let [[kind button state] (.split (.toString message) " ")]
           (when (and (= kind "button") (= (js/parseInt state) 1))
             (press-key app-state (get socket-button-map (js/parseInt button)))))))

  lcd
  ; using LCD button interface on device
  (.on lcd "button_down"
       (fn [button]
         (press-key app-state (get lcd-button-map button))))

  :else
  ; using console tty for development
  (do
    (.setRawMode process/stdin true)
    (.resume process/stdin)
    (.setEncoding process/stdin "utf8")
    (.on process/stdin "data"
         (fn [k]
           (let [v (.toString (js/Buffer. k) "hex")]
             ; check for channel toggle (number keys)
             (if (contains? (set (map #(str (+ 31 %)) (range 9))) v)
               (toggle-channel! (dec (int (str k))) app-state)
               ; check for navigation keys
               (case v
                 "1b5b44" (press-key app-state :left)
                 "1b5b43" (press-key app-state :right)
                 "1b5b41" (press-key app-state :up)
                 "1b5b42" (press-key app-state :down)
                 "20" (press-key app-state :select)
                 "0d" (press-key app-state :select)
                 "1b" (process/exit)
                 "03" (process/exit)
                 (js/console.log "key" v))))))))

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
                tick-freq (get-in @state [:player :tick-freq])
                time-hw-ms (.-time_hw s)
                time-alsa-delay-ms (.-time_alsa_delay s)
                bpm (.-bpm s)]
            (when (and (= (mod row tick-freq) 0)
                       (not= row last-row))
              (let [next-tick-ms (- time-alsa-delay-ms (- (.getTime (js/Date.)) time-hw-ms))]
                (js/setTimeout send-pulse (max 0 next-tick-ms))
                (swap! state #(-> %
                                  (assoc-in [:sync :last-row] row)
                                  (assoc-in [:player :bpm] bpm)))))))))))

; ***** init ***** ;

(defn main []
  ; start the xmp manager loop
  (go
    (loop [player nil]
      (let [[action & args] (<! (@app-state :player-chan))]
        (case action
          ; TODO: toggle pause
          ; (p.write " ")
          :play
          (let [[module-file] args
                channels (-> @app-state :player :channels)
                muted (map first (remove second (map-indexed #(vec [%1 %2]) channels)))
                muted-args (if (count muted) ["-M" (clojure.string/join "," muted)] nil)
                xmp-args (concat ["-l" module-file] muted-args)]
            ; kill the old xmp session
            (when player (.kill player))
            (if (= module-file :stop)
              (recur nil)
              (let [new-player (node-pty/spawn "./xmp-wrap" (clj->js xmp-args) #js {:env process/env})]
                (.on new-player "data" (partial got-player-data! app-state))
                (recur new-player))))
          :unmute
          (let [[channel] args]
            (when player
              (if (= channel :all)
                (.write player "!")
                (.write player (str (inc channel)))))
            (recur player))))))

  ; what to do when mutation happens
  (add-watch app-state
             :ui-changes
             (fn [k a old-state new-state]
               ; when active module changes send to xmp manager loop
               (when (changed? (old-state :player) (new-state :player) [:playing :play-state])
                 (put! (@app-state :player-chan)
                       [:play
                        (if (= (-> new-state :player :play-state) "play")
                          (-> new-state :player :playing)
                          :stop)]))
               ; update the user interface
               (when (changed? old-state new-state [:player :display :modules])
                 (update-ui! new-state))))

  ; set the initial state after loading in module list
  (swap! app-state
         (fn [old-state]
           (let [mod-files (find-mod-files modfile-dirs)]
             (-> old-state
                 (assoc :modules mod-files)
                 (assoc-in [:player :playing] (first mod-files))
                 (assoc-in [:display :module] (first mod-files)))))))

; listen for "reload" events when a USB stick is mounted by udev
(let [server (.createSocket dgram "udp4")]
  (.on server "listening"
       (fn []
         (.on server "message"
              (fn [message remote]
                (let [mod-files (find-mod-files modfile-dirs)]
                  (swap! app-state assoc :modules mod-files))))))
  (.bind server 2992 "127.0.0.1"))

; avoid missing goog.global.setTimeout
; https://dev.clojure.org/jira/browse/ASYNC-110
(js/setTimeout main 0)
