#!./node_modules/.bin/lumo
#!/usr/bin/env lumo
(ns xmp.core
  (:require
    [process]
    [clojure.string]
    [child_process]
    [node-pty]))

(enable-console-print!)

(console.log "xmp.cljs")

(def re-line #"^.*([A-Z0-9]{2})\/([A-Z0-9]{2})\] Chn\[")
(def re-bpm #"Speed\[(.*?)\] BPM\[(.*?)\]")

(def find-args "-maxdepth 3 -type f \\( -iname *.xm -o -iname *.it -o -iname *.s3m -o -iname *.mod -o -iname *.med -o -iname *.oct -o -iname *.ahx \\)")

(def in-lumo (>= (.indexOf (get process/argv 0) "lumo") 0))
(def args (.slice process/argv (if in-lumo 3 2)))

(defn find-mod-files [dirs]
  (let [find-cmd (str "find '" (clojure.string/join "' '" dirs) "' " find-args)]
    (.split (.toString (child_process/execSync find-cmd)) "\n")))


; TODO:
;  * TC - automount USB
;  * LCD UI
;  * sync signal

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

(let [p (node-pty/spawn "./xmp-wrap" #js ["-l" "./test.it"] process/env)]
  (.on p "data"
       (fn [data]
         (let [[match-bpm subticks rate] (re-find re-bpm data)
               [match-tick tick len] (re-find re-line data)]
           ;(println "---> LINE:" data)
           (if match-bpm (println "---> BPM:" subticks (js/parseInt rate 16)))
           (if match-tick (println "---> Match:" tick len))))))

; toggle channel
; (p.write "1")

; unmute all
; (p.write "!")

; toggle pause
; (p.write " ")

