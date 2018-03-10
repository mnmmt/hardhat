#!./node_modules/.bin/lumo
#!/usr/bin/env lumo
(ns xmp.core
  (:require
    [process]
    [node-pty]))

(enable-console-print!)

(console.log "xmp.cljs")

(def re-line #"^.*([A-Z0-9]{2})\/([A-Z0-9]{2})\] Chn\[")
(def re-bpm #"Speed\[(.*?)\] BPM\[(.*?)\]")

(let [p (node-pty/spawn "xmp" #js ["-l" "./test.it"] process/env)]
  (.on p "data"
       (fn [data]
         (let [[match-bpm subticks rate] (re-find re-bpm data)
               [match-tick tick len] (re-find re-line data)]
           (if match-bpm (println "---> BPM:" subticks (js/parseInt rate 16)))
           (if match-tick (println "---> Match:" tick len))
           ;(println "---> LINE:" data)
           
           )))
  ;(p.write "12")
  )


