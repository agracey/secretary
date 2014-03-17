(ns secretary.core
  (:require [clojure.string :as string]
            [cljs.reader :refer [read-string]]))

;;----------------------------------------------------------------------
;; Protocols

(defprotocol IRouteMatches
  (route-matches [this route]))

(defprotocol IRenderRoute
  (render-route
    [this]
    [this params]))

;;----------------------------------------------------------------------
;; Configuration

(def ^:dynamic *config*
  (atom {:prefix ""}))

(defn get-config
  "Gets a value for *config* at path."
  [path]
  (let [path (if (sequential? path) path [path])]
    (get-in @*config* path)))

(defn set-config!
  "Associates a value val for *config* at path."
  [path val]
  (let [path (if (sequential? path) path [path])]
    (swap! *config* assoc-in path val)))

;;----------------------------------------------------------------------
;; Parameter encoding/decoding

(def encode js/encodeURIComponent)
(def decode js/decodeURIComponent)

(defmulti ^:private encode-pair
  (fn [[k v]]
    (cond
     (or (sequential? v) (set? v))
     ::sequential
     (or (map? v) (satisfies? IRecord v))
     ::map)))

(defn- key-index
  ([k] (str (name k) "[]"))
  ([k index]
     (str (name k) "[" index "]")))

(defmethod encode-pair ::sequential [[k v]]
  (let [encoded (map-indexed
                 (fn [i x]
                   (let [pair (if (coll? x)
                                [(key-index k i) x]
                                [(key-index k) x])]
                     (encode-pair pair)))
                 v)]
    (string/join \& encoded)))

(defmethod encode-pair ::map [[k v]]
  (let [encoded (map
                 (fn [[ik iv]]
                   (encode-pair [(key-index k (name ik)) iv]))
                 v)]
    (string/join \& encoded)))

(defmethod encode-pair :default [[k v]]
  (str (name k) \= (encode (str v))))

(defn- parse-path [path]
  (let [index-re #"\[([^\]]*)\]*" ;; Parse out the index value
        parts (re-seq index-re path)]
    (map
     (fn [[_ part]]
       (cond
        (empty? part) 0
        (re-matches #"\d+" part) (read-string part)
        :else part))
     parts)))

(defn- key-parse
  "Return a key path for a serialized query-string entry.

  Ex.

    (key-parse \"foo[][a][][b]\")
    ;; => (\"foo\" 0 \"a\" 0 \"b\")
  "
  [k]
  (let [re #"([^\[\]]+)((?:\[[^\]]*\])*)?"
        [_ key path] (re-matches re k)
        parsed-path (parse-path (str path))]
    (cons key parsed-path)))

(defn- assoc-in-query-params
  "Like assoc-in but numbers in path create vectors instead of maps.

  Ex.

    (assoc-in* {} [\"foo\" 0] 1)
    ;; => {\"foo\" [1]}

    (assoc-in* {} [\"foo\" 0 \"a\"] 1)
    ;; => {\"foo\" [{\"a\" 1}]}
  "
  [m path v]
  (let [heads (fn [xs]
                (map-indexed
                 (fn [i _]
                   (take (inc i) xs))
                 xs))
        hs (heads path)
        m (reduce
           (fn [m h]
             (if (and (or (number? (last h)))
                      (not (vector? (get-in m (butlast h)))))
               (assoc-in m (butlast h) [])
               m))
           m
           hs)]
    (if (zero? (last path))
      (update-in m (butlast path) conj v)
      (assoc-in m path v))))

(defn encode-query-params
  "Turns a map of query parameters into url encoded string."
  [query-params]
  (string/join \& (map encode-pair query-params)))

(defn decode-query-params
  "Extract a map of query parameters from a query string."
  [query-string]
  (let [parts (string/split query-string #"&")]
    (reduce
     (fn [m part]
       (let [[k v] (string/split part #"=" 2)]
         (assoc-in-query-params m (key-parse k) (decode v))))
     {}
     parts)))

(defn encode-uri
  "Like js/encodeURIComponent excepts ignore slashes."
  [uri]
  (->> (string/split uri #"/")
       (map encode)
       (string/join "/")))

;;----------------------------------------------------------------------
;; Route compilation

;; The implementation for route compilation was inspired by Clout and
;; modified to suit JavaScript and Secretary.
;; SEE: https://github.com/weavejester/clout

(defn- re-matches*
  "Like re-matches but result is a always vector. If re does not
  capture matches then it will return a vector of [m m] as if it had a
  single capture. Other wise it maintains consistent behavior with
  re-matches. "
  [re s]
  (let [ms (clojure.core/re-matches re s)]
    (when ms
      (if (sequential? ms) ms [ms ms]))))

(def ^:private re-escape-chars
  (set "\\.*+|?()[]{}$^"))

(defn- re-escape [s]
 (reduce
  (fn [s c]
    (if (re-escape-chars c)
      (str s \\ c)
      (str s c)))
  ""
  s))

(defn- lex*
  "Attempt to lex a single token from s with clauses. Each clause is a
  pair of [regexp action] where action is a function. regexp is
  expected to begin with ^ and contain a single capture. If the
  attempt is successful a vector of [s-without-token (action capture)]
  is returned. Otherwise the result is nil."
  [s clauses]
  (some
   (fn [[re action]]
     (when-let [[m c] (re-find re s)]
       [(subs s (count m)) (action c)]))
   clauses))

(defn- lex-route
  "Return a pair of [regex params]. regex is a compiled regular
  expression for matching routes. params is a list of route param
  names (:*, :id, etc.). "
  [s clauses]
  (loop [s s pattern "" params []]
    (if (seq s)
      (let [[s [r p]] (lex* s clauses)]
        (recur s (str pattern r) (conj params p)))
      [(re-pattern (str \^ pattern \$)) (remove nil? params)])))

(defn- compile-route
  "Given a route return an instance of IRouteMatches."
  [route]
  (let [clauses [[#"^\*([^\s.:*/]*)" ;; Splats, named splates
                  (fn [v]
                    (let [r "(.*?)"
                          p (if (seq v)
                              (keyword v)
                              :*)]
                      [r p]))]
                 [#"^\:([^\s.:*/]+)" ;; Params
                  (fn [v]
                    (let [r "([^,;?/]+)"
                          p (keyword v)]
                      [r p]))]
                 [#"^([^:*]+)" ;; Literals
                  (fn [v]
                    (let [r (re-escape v)]
                      [r]))]]
       [re params] (lex-route route clauses)]
   (reify IRouteMatches
     (route-matches [_ route]
       (when-let [[_ & ms] (re-matches* re route)]
         (->> (interleave params (map decode ms))
              (partition 2)
              (merge-with vector {})))))))

;;----------------------------------------------------------------------
;; Route rendering

(defn ^:internal render-route* [obj & args]
  (when (satisfies? IRenderRoute obj)
    (apply render-route obj args)))

;;----------------------------------------------------------------------
;; Routes adding/removing

(def ^:dynamic *routes*
  (atom []))

(defn add-route! [obj action]
  (let [obj (if (string? obj)
              (compile-route obj)
              obj)]
    (swap! *routes* conj [obj action])))

(defn remove-route! [obj]
  (swap! *routes*
         (fn [rs]
           (filterv
            (fn [[x _]]
              (not= x obj))
            rs))))

(defn reset-routes! []
  (reset! *routes* []))

;;----------------------------------------------------------------------
;; Route lookup and dispatch

(defn locate-route [route]
  (some
   (fn [[compiled-route action]]
     (when-let [params (route-matches compiled-route route)]
       [action (route-matches compiled-route route)]))
   @*routes*))

(defn dispatch!
  "Dispatch an action for a given route if it matches the URI path."
  [uri]
  (let [[uri-path query-string] (string/split uri #"\?")
        query-params (when query-string
                       {:query-params (decode-query-params query-string)})
        [action params] (locate-route uri-path)
        action (or action identity)
        params (merge params query-params)]
    (action params)))

;;----------------------------------------------------------------------
;; Protocol implementations

(extend-protocol IRouteMatches
  string
  (route-matches [this route]
    (route-matches (compile-route this) route))

  js/RegExp
  (route-matches [this route]
    (when-let [[_ & ms] (re-matches* this route)]
      (vec ms))))

(extend-protocol IRenderRoute
  string
  (render-route [this]
    (render-route this {}))

  (render-route [this params]
    (let [{:keys [query-params] :as m} params
          a (atom m)
          path (.replace this (js/RegExp. ":[^\\s.:*/]+|\\*[^\\s.:*/]*" "g")
                         (fn [$1]
                           (let [lookup (keyword (if (= $1 "*")
                                                   $1
                                                   (subs $1 1)))
                                 v (@a lookup)
                                 replacement (if (sequential? v)
                                               (do
                                                 (swap! a assoc lookup (next v))
                                                 (encode-uri (first v)))
                                               (if v (encode-uri v) $1))]
                             replacement)))
          path (str (get-config [:prefix]) path)]
      (if-let [query-string (and query-params
                                 (encode-query-params query-params))]
        (str path "?" query-string)
        path))))
