(ns arachne.aristotle.registry
  "Tools for mapping between IRIs and keywords"
  (:require [clojure.string :as str])
  (:refer-clojure :exclude [find alias])
  (:import [org.apache.jena.rdf.model.impl Util]))

;; Note: it would potentially be more performant to use a trie or
;; prefix tree instead of a normal map for the inverse prefix
;; tree. Punting until it becomes a problem.

(defonce ^:dynamic *registry* {:prefixes {}
                               :prefixes' {}
                               :aliases {}
                               :aliases' {}})

(defn- tokenize-ns [kw]
  (str/split (namespace kw) #"\."))


(defn- by-prefix
  "Return the IRI for a namespace matching a prefix in the registry tree."
  [registry kw]
  (when (namespace kw)
    (loop [registry registry
           [segment & more-segments] (tokenize-ns kw)]
      (when-let [match (get registry segment)]
        (cond
          (empty? more-segments) (cond
                                   (::= match) (str (::= match) (name kw))
                                   (get match "*") (str (get match "*") (name kw))
                                   :else nil)
          (get match (first more-segments)) (recur match more-segments)
          (contains? match "*") (str (get match "*")
                                     (str/join "/" more-segments)
                                     (when-not (empty? more-segments) "/") (name kw)))))))

(defn- longest-prefix
  "Find the longest matching substring"
  [prefix-list s]
  (reduce (fn [curr prefix]
            (if (str/starts-with? s prefix)
              (if curr
                (if (< (.length curr) (.length prefix))
                  prefix
                  curr)
                prefix)
              curr))
          nil prefix-list))

(defn- lookup-prefix
  "Construct a keyword from an IRI using the prefix tree, returns nil if not possible."
  [registry iri]
  (when-let [prefix (longest-prefix (keys registry) iri)]
    (let [fragment (subs iri (.length prefix))
          fragment-seq (str/split fragment #"/")
          registration (get registry prefix)
          wild? (= "*" (last registration))]
      (if (not wild?)
        (when (= 1 (count fragment-seq)) (keyword (str/join "." registration) fragment))
        (keyword
         (str/join "." (concat (drop-last registration)
                               (drop-last fragment-seq)))
         (last fragment-seq))))))

(defn iri
  "Given a keyword, build a corresponding IRI, throwing an exception if this is not possible."
  [kw]
  (or (-> *registry* :aliases (get kw))
      (by-prefix (:prefixes *registry*) kw)
      (throw (ex-info (format "Could not determine IRI for %s, no namespace, namespace prefix or alias found." kw)
                      {:keyword kw}))))

(defn kw
  "Return a keyword representing the given IRI. Returns nil if no matching
   keyword or namespace could be found in the registry."
  [iri]
  (or (-> *registry* :aliases' (get iri))
      (lookup-prefix (:prefixes' *registry*) iri)
      nil))

(defn- assoc-in-uniquely
  "Like assoc-in, but (if prevent-overrides is true) throws an exception instead of overwriting an existing value"
  [m prevent-overrides? ks v]
  (update-in m ks (fn [e]
                    (when (and prevent-overrides? e (not= e v))
                      (throw (ex-info (format "Could not map %s to %s, already mapped to %s"
                                              ks v e)
                                      {:value v
                                       :existing e
                                       :keyseq ks})))
                    v)))

;; TODO: it shouldn't be possible to conflict with a non-wildcard
;; registration. The two can coexist.

;; TODO: we need to store non-wildcard registrations as a distinct map form. Not the same as a wildcard, but indicated somehow other than a non-associable form.

(defn add-prefix
  "Return an updated registry map with the given prefix mapping."
  [registry prevent-overrides? namespace prefix]
  (let [segments (vec (str/split (name namespace) #"\."))]
    (-> registry
        (update :prefixes assoc-in-uniquely prevent-overrides? (if (= "*" (last segments))
                                                                 segments
                                                                 (conj segments ::=)) prefix)
        (update :prefixes' assoc-in-uniquely prevent-overrides? [prefix] segments))))

(defn add-alias
  [registry prevent-overrides? kw iri]
  (-> registry
      (update :aliases assoc-in-uniquely prevent-overrides? [kw] iri)
      (update :aliases' assoc-in-uniquely prevent-overrides? [iri] kw)))

(defn prefix
  "Register a namespace as an RDF IRI prefix."
  [namespace iri-prefix]
  (alter-var-root #'*registry* add-prefix true namespace iri-prefix))

(defn alias
  "Register a mapping between a keyword and a specific IRI"
  [kw iri]
  (alter-var-root #'*registry* add-alias true kw iri))

(defrecord Prefix [prefix iri])

(defn read-prefix
  "Constructor for a prefix, called by data reader."
  [[prefix iri]]
  (->Prefix prefix iri))

(defn install-prefix
  "Install a prefix in the thread-local data reader"
  [prefix]
  (set! *registry* (add-prefix *registry* false (:prefix prefix) (:iri prefix))))

(defmacro with
  "Execute the supplied function with the specified prefix map in the thread-local registry"
  [prefix-map & body]
  `(binding [*registry* (reduce (fn [r# [ns# prefix#]]
                                  (add-prefix r# false ns# prefix#))
                                *registry*
                                ~prefix-map)]
     ~@body))

(prefix 'rdf "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
(prefix 'rdfs "http://www.w3.org/2000/01/rdf-schema#")
(prefix 'xsd "http://www.w3.org/2001/XMLSchema#")
(prefix 'owl "http://www.w3.org/2002/07/owl#")
(prefix 'owl2 "http://www.w3.org/2006/12/owl2#")
