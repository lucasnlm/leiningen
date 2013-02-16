(ns leiningen.core.main
  (:require [leiningen.core.user :as user]
            [leiningen.core.project :as project]
            [leiningen.core.classpath :as classpath]
            [leiningen.core.utils :as utils]
            [leiningen.core.logger :as log]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [bultitude.core :as b]))

(def aliases {"-h" "help", "-help" "help", "--help" "help", "-?" "help",
              "-v" "version", "-version" "version", "--version" "version",
              "überjar" "uberjar",
              ;; TODO: these should use add-profile instead
              "-o" ["with-profile" "offline,dev,user,default"]
              "-U" ["with-profile" "update,dev,user,default"]
              "cp" "classpath" "halp" "help"
              "with-profiles" "with-profile"
              "readme" ["help" "readme"]
              "tutorial" ["help" "tutorial"]
              "sample" ["help" "sample"]})

(def ^:private profile-aliases
  "User profile aliases, used only when Lein is not within a project."
  (atom (-> (user/profiles) :user :aliases)))

(defn- get-and-dissoc!
  "Returns a value associated with a key in a hash map contained in an atom,
  removing it if it exists."
  [atom key]
  (when-let [[k v] (find @atom key)]
    (swap! atom dissoc key)
    v))

(defn lookup-alias [task-name project & [not-found]]
  (or (aliases task-name)
      (get (:aliases project) task-name)
      (when-not project
        (get-and-dissoc! profile-aliases task-name))
      task-name
      (or not-found "help")))

(defn task-args [args project]
  (if (= "help" (aliases (second args)))
    ["help" [(first args)]]
    [(lookup-alias (first args) project) (rest args)]))

(defn- distance [s t]
  (letfn [(iters [n f start]
            (take n (map second
                         (iterate f start))))]
    (let [m (inc (count s)), n (inc (count t))
          first-row (vec (range m))
          matrix (iters n (fn [[j row]]
                            [(inc j)
                             (vec (iters m (fn [[i col]]
                                             [(inc i)
                                              (if (= (nth s i)
                                                     (nth t j))
                                                (get row i)
                                                (inc (min (get row i)
                                                          (get row (inc i))
                                                          col)))])
                                         [0 (inc j)]))])
                        [0 first-row])]
      (last (last matrix)))))

(defn tasks
  "Return a list of symbols naming all visible tasks."
  []
  (->> (b/namespaces-on-classpath :prefix "leiningen")
       (filter #(re-find #"^leiningen\.(?!core|main|util)[^\.]+$" (name %)))
       (distinct)
       (sort)))

(defn suggestions
  "Suggest possible misspellings for task from list of tasks."
  [task tasks]
  (let [suggestions (into {} (for [t tasks
                                   :let [n (.replaceAll (name t)
                                                        "leiningen." "")]]
                               [n (distance n task)]))
        min (apply min (vals suggestions))]
    (if (<= min 4)
      (map first (filter #(= min (second %)) suggestions)))))

(defn ^:no-project-needed task-not-found [task & _]
  (println (str "'" task "' is not a task. See 'lein help'."))
  (when-let [suggestions (suggestions task (tasks))]
    (println)
    (println "Did you mean this?")
    (doseq [suggestion suggestions]
      (println "        " suggestion)))
  (log/abort))

;; TODO: got to be a cleaner way to do this, right?
(defn- drop-partial-args [pargs]
  #(for [[f & r] %
         :let [non-varargs (if (pos? (inc (.indexOf (or r []) '&)))
                             (min (count pargs) (.indexOf r '&))
                             (count pargs))]]
     (cons f (drop non-varargs r))))

(defn resolve-task
  "Look up task function and perform partial application if applicable."
  ([task not-found]
     (let [[task & pargs] (if (coll? task) task [task])]
       (if-let [task-var (utils/require-resolve (str "leiningen." task) task)]
         (with-meta
           (fn [project & args] (apply task-var project (concat pargs args)))
           (update-in (meta task-var) [:arglists] (drop-partial-args pargs)))
         (not-found task))))
  ([task] (resolve-task task #'task-not-found)))

(defn ^:internal matching-arity? [task args]
  (some (fn [parameters]
          (and (if (= '& (last (butlast parameters)))
                 (>= (count args) (- (count parameters) 3))
                 (= (count parameters) (inc (count args))))
               parameters))
        (:arglists (meta task))))

(defn- remove-alias
  "Removes an alias from the specified project and its metadata (which lies
   within :without-profiles) to avoid recursive alias calls."
  [project alias]
  (-> project
      (update-in [:aliases] dissoc alias)
      (vary-meta update-in [:without-profiles :aliases] dissoc alias)))

(defn apply-task
  "Resolve task-name to a function and apply project and args if arity matches."
  [task-name project args]
  (let [[task-alias] (for [[k v] (:aliases project) :when (= v task-name)] k)
        project (and project (remove-alias project
                                           (or task-alias task-name)))
        task (resolve-task task-name)]
    (when-not (or project (:no-project-needed (meta task)))
      (log/abort "Couldn't find project.clj, which is needed for" task-name))
    (when-not (matching-arity? task args)
      (log/abort 
        "Wrong number of arguments to" task-name "task.\n"
        "Expected" (string/join " or " (map next (:arglists (meta task))))))
    (log/debug "Applying task" task-name "to" args)
    (apply task project args)))

(defn leiningen-version []
  (System/getenv "LEIN_VERSION"))

(defn ^:internal version-satisfies? [v1 v2]
  (let [v1 (map #(Integer. %) (re-seq #"\d+" (first (string/split v1 #"-" 2))))
        v2 (map #(Integer. %) (re-seq #"\d+" (first (string/split v2 #"-" 2))))]
    (loop [versions (map vector v1 v2)
           [seg1 seg2] (first versions)]
      (cond (empty? versions) true
            (= seg1 seg2) (recur (rest versions) (first (rest versions)))
            (> seg1 seg2) true
            (< seg1 seg2) false))))

(def ^:private min-version-warning
  "This project requires Leiningen %s, but you have %s ***

Get the latest version of Leiningen at http://leiningen.org or by executing
\"lein upgrade\".")

(defn- verify-min-version
  [{:keys [min-lein-version]}]
  (when-not (version-satisfies? (leiningen-version) min-lein-version)
    (log/info (format min-version-warning min-lein-version (leiningen-version)))))

(defn- warn-chaining [task-name args]
  (when (and (some #(.endsWith (str %) ",") (cons task-name args))
             (not-any? #(= % "do") (cons task-name args)))
    (log/warn 
      "task chaining has been moved to the \"do\" task. \n"
      "For example, \"lein javac, test\" should now be called"
      "as \"lein do javac, test\"\n"
      "See `lein help do` for details.")))

(defn user-agent []
  (format "Leiningen/%s (Java %s; %s %s; %s)"
          (leiningen-version) (System/getProperty "java.vm.name")
          (System/getProperty "os.name") (System/getProperty "os.version")
          (System/getProperty "os.arch")))

(defn- configure-http []
  "Set Java system properties controlling HTTP request behavior."
  (System/setProperty "aether.connector.userAgent" (user-agent))
  (when-let [{:keys [host port non-proxy-hosts]} (classpath/get-proxy-settings)]
    (System/setProperty "http.proxyHost" host)
    (System/setProperty "http.proxyPort" (str port))
    (when non-proxy-hosts
      (System/setProperty "http.nonProxyHosts" non-proxy-hosts)))
  (when-let [{:keys [host port]} (classpath/get-proxy-settings "https_proxy")]
    (System/setProperty "https.proxyHost" host)
    (System/setProperty "https.proxyPort" (str port))))

(defn -main
  "Command-line entry point."
  [& raw-args]
  (try
    (user/init)
    (let [project (if (.exists (io/file "project.clj"))
                    (project/init-project (project/read)))
          [task-name args] (task-args raw-args project)]
      (when (:min-lein-version project)
        (verify-min-version project))
      (configure-http)
      (when-not project
        ;; We don't use merge-profiles because we don't want to apply middleware
        ;; since middleware won't be ready until plugins are loaded.
        (let [dummy (-> (project/make (:user (user/profiles)))
                        (project/init-profiles [:base :user]))]
          (project/load-certificates dummy)
          (project/load-plugins dummy)))
      (warn-chaining task-name args)
      (apply-task task-name project args))
    (catch Exception e
      (if (or log/*debug* (not (:exit-code (ex-data e))))
        (.printStackTrace e)
        (println (.getMessage e)))
      (log/exit (:exit-code (ex-data e) 1))))
  (log/exit 0))
