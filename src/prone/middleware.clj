(ns prone.middleware
  "The Prone middleware can be used to catch exceptions from your app and
  present them in an interactive explorer UI. Stack traces will by default
  be filtered to focus on your app's stack frames, and environment data is
  available for inspection, along with data passed to Prone via
  prone.debug/debug"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [prone.debug :as debug]
            [prone.hiccough :refer [render]]
            [prone.prep :refer [prep-debug-page prep-error-page]]
            [prone.stacks :refer [normalize-exception]]))

(defn find-application-name-in-project-clj [s]
  (when-let [n (second (re-find #"\s*\(defproject\s+([^\s]+)" s))]
    (symbol n)))

(defn- get-application-name
  "Assume that prone is being used from a leiningen project, and fetch the
  application name from project.clj - this allows prone to differentiate
  application frames from libraries/runtime frames."
  []
  (find-application-name-in-project-clj (slurp "project.clj")))

(defn- random-string-not-present-in
  "Look for a random string that is not present in haystack, increasing the
   length for each failed attempt."
  [haystack length]
  (let [needle (->> #(rand-nth "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                    repeatedly
                    (take length)
                    (apply str))]
    (if (.contains haystack needle)
      (recur haystack (inc length))
      needle)))

(defn- load-asset [name path]
  (let [contents (slurp (io/resource path))]
    {:name name
     :contents contents
     :url (str "/prone/" (hash contents) "/" path)}))

(defonce assets [(load-asset :styles "prone.css")
                 (load-asset :libs "prone-lib.js")
                 (load-asset :code "prone/generated/prone.js")])

(defonce asset-name->url (into {} (map (juxt :name :url) assets)))
(defonce asset-url->contents (into {} (map (juxt :url :contents) assets)))

(defn- render-page [data]
  (render
   (let [data-str (prn-str data)
         script-replacement-string (random-string-not-present-in data-str 6)]
     (list "<!DOCTYPE html>"
           [:html
            [:head
             [:title (:title data)]
             [:link {:rel "stylesheet" :href (asset-name->url :styles)}]]
            [:body
             [:div {:id "ui-root"}]
             [:input {:type "hidden" :id "script-replacement-string" :value script-replacement-string}]
             [:script {:type "text/json" :id "prone-data"} (str/replace data-str #"\bscript\b" script-replacement-string)]
             [:script {:src (asset-name->url :libs)}]
             [:script {:src (asset-name->url :code)}]]]))))

(defonce pages (atom {}))

(defn- store-page [page]
  (let [uri (str "/prone/" (.toString (java.util.UUID/randomUUID)))]
    (swap! pages assoc uri page)
    (assoc page :uri uri)))

(defn- serve-page [page & [status]]
  {:status (or status 500)
   :body (render-page page)
   :headers (cond-> {"Content-Type" "text/html"}
              (:uri page) (assoc "Link" (str "<" (:uri page) ">; rel=help")))})

(defn debug-response
  "Ring Response for prone debug data."
  [req data]
  (-> data
      (prep-debug-page req)
      store-page
      (serve-page 203)))

(defn exceptions-response
  "Ring Response for prone exceptions data."
  [req e app-namespaces]
  (-> e
      normalize-exception
      (prep-error-page @debug/*debug-data*
                       req
                       (or app-namespaces [(get-application-name)]))
      store-page
      serve-page))

(defn wrap-exceptions
  "Let Prone handle exeptions instead of Ring. This way, instead of a centered
   stack trace, errors will give you a nice interactive page where you can browse
   data, filter the stack trace and generally get a good grip of what is
   happening.

   Optionally, supply a opts map to specify namespaces to include and
   a predicate function to exclude certain requests from prone e.g.:

   => (wrap-exceptions handler {:app-namespaces ['your-ns-1 'my.ns.to-show]
                                :skip-prone? (fn [req] (not-browser? req)})"
  [handler & [{:keys [app-namespaces skip-prone?] :as opts}]]
  (fn [req]
    (if-let [page (get @pages (:uri req))]
      (serve-page page 200)
      (if-let [asset (asset-url->contents (:uri req))]
        {:body asset :status 200 :headers {"Cache-Control" "max-age=315360000"}}
        (binding [debug/*debug-data* (atom [])]
          (if (and skip-prone? (skip-prone? req))
            (handler req)
            (try
              (let [result (handler req)]
                (if (< 0 (count @debug/*debug-data*))
                  (debug-response req @debug/*debug-data*)
                  result))
              (catch Exception e
                (.printStackTrace e)
                (exceptions-response req e app-namespaces)))))))))
