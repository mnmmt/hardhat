(require 'lumo.build.api)

(lumo.build.api/build "src"
                      {:output-to "xmp.js"
                       :target :nodejs
                       :optimizations :simple
                       :verbose true})

