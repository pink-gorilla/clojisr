(ns clojisr.v1.applications.plotting
  (:require [clojisr.v1.r :refer [r]]
            [clojisr.v1.util :refer [exception-cause]]
            [clojisr.v1.require :refer [require-r]]
            [clojure.tools.logging.readable :as log]
            [clojisr.v1.gorilla-util :refer [fix-svg]])
  (:import [java.io File]
           [clojisr.v1.robject RObject]
           [java.awt Graphics2D Image]
           [java.awt.image BufferedImage]
           [javax.swing ImageIcon]))

(require-r '[grDevices])

(def ^:private files->fns (let [devices (select-keys (ns-publics 'r.grDevices) '[pdf png svg jpeg tiff bmp])]
                            (if-let [jpg (get devices 'jpeg)]
                              (assoc devices 'jpg jpg)
                              devices)))

(def ^:private r-print (r "print")) ;; avoid importing `base` here

(defn plot->file
  [filename plotting-function-or-object & device-params]
  (let [apath (.getAbsolutePath (File. filename))
        extension (symbol (or (second (re-find #"\.(\w+)$" apath)) :no))
        device (files->fns extension)]
    (if-not (contains? files->fns extension)
      (log/warn [::plot->file {:message (format "%s filetype is not supported!" (name extension))}])
      (try
        (apply device :filename apath device-params)
        (try
          (if (instance? RObject plotting-function-or-object)
            (r-print plotting-function-or-object)
            (plotting-function-or-object))
          (catch Exception e
            (log/warn [::plot->file {:message "Evaluation plotting function failed."
                                     :exception (exception-cause e)}]))
          (finally (r.grDevices/dev-off)))
        (log/debug [[::plot->file {:message (format "File %s saved." apath)}]])
        (catch clojure.lang.ExceptionInfo e (throw e))
        (catch Exception e (log/warn [::plot->file {:message (format "File creation (%s) failed" apath)
                                                    :exception (exception-cause e)}]))))))

(defn plot->svg [plotting-function-or-object & svg-params]
  (let [tempfile (File/createTempFile "clojisr_plot" ".svg")
        path     (.getAbsolutePath tempfile)]
    (apply plot->file path plotting-function-or-object svg-params)
    (let [result (slurp path)]
      (.delete tempfile)
      result)))

(defn ->svg [wrapper-params plotting-function-or-object & svg-params]
  (let [tempfile (File/createTempFile "clojisr_notebook_plot" ".svg")
        path     (.getAbsolutePath tempfile)
        {:keys [width height]} wrapper-params
        ]
    (apply plot->file path plotting-function-or-object svg-params)
    (let [result (slurp path)]
      (.delete tempfile)
      ;^:R [:p/html (fix-svg result 300 200)]
      ^:R [:div.clojsrplot (fix-svg result width height)]
      
      )))

(defn- force-argb-image
  "Create ARGB buffered image from given image."
  [^Image img]
  (let [^BufferedImage bimg (BufferedImage. (.getWidth img nil) (.getHeight img nil) BufferedImage/TYPE_INT_ARGB)
        ^Graphics2D gr (.createGraphics bimg)]
    (.drawImage gr img 0 0 nil)
    (.dispose gr)
    (.flush img)
    bimg))

(defn plot->buffered-image [plotting-function-or-object & png-params]
  (let [tempfile (File/createTempFile "clojisr_plot" ".png")
        path     (.getAbsolutePath tempfile)]
    (apply plot->file path plotting-function-or-object png-params)
    (let [result (force-argb-image (.getImage (ImageIcon. path)))]
      (.delete tempfile)
      result)))
