;;
;; Copyright (c) Huahai Yang. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file LICENSE at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.
;;

(ns editscript.diff.quick
  (:require [clojure.set :as set]
            [editscript.edit :as e]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(declare diff*)

(defn- diff-map
  [script path a b]
  (reduce-kv
   (fn [_ ka va]
     (let [path' (conj path ka)]
       (if (contains? b ka)
        (diff* script path' va (get b ka))
        (diff* script path' va (e/nada)))))
   nil
   a)
  (reduce-kv
   (fn [_ kb vb]
     (when-not (contains? a kb)
       (diff* script (conj path kb) (e/nada) vb)))
   nil
   b))

(defn- vec-edits*
  "Based on 'Wu, S. et al., 1990, An O(NP) Sequence Comparison Algorithm,
  Information Processing Letters, 35:6, p317-23.'

  A greedy algorithm, attempting to get to the furthest points with a given
  number of edits. Very fast. However, it does not have replacement operations,
  so it is not very useful for nested trees. It can also only do unit cost for
  addition and deletion. "
  [a b ^long n ^long m]
  (let [delta (- n m)
        snake (fn [^long k ^long x]
                (loop [x x y (- x k)]
                  (let [ax (get a x) by (get b y)]
                    (if (and (< x n)
                             (< y m)
                             (= (type ax) (type by))
                             (= ax by)
                             )
                      (recur (inc x) (inc y))
                      x))))
        fp-fn (fn [fp ^long k]
                (let [[dk-1 vk-1] (get fp (dec k) [-1 []])
                      dk-1        (inc ^long dk-1)
                      [dk+1 vk+1] (get fp (inc k) [-1 []])
                      x           (max dk-1 ^long dk+1)
                      ^long sk    (snake k x)
                      ops         (let [es (if (> dk-1 ^long dk+1)
                                             (conj vk-1 :-)
                                             (conj vk+1 :+))]
                                    (if (> sk x)
                                      (conj es (- sk x))
                                      es))]
                  (assoc! fp k [sk ops])))
        fp    (loop [p 0 fp (transient {})]
                (let [fp (loop [k (* -1 p) fp fp]
                           (if (< k delta)
                             (recur (inc k) (fp-fn fp k))
                             fp))
                      fp (loop [k (+ delta p) fp fp]
                           (if (< delta k)
                             (recur (dec k) (fp-fn fp k))
                             fp))
                      fp (fp-fn fp delta)]
                  (if-not (= n (first (get fp delta)))
                    (recur (inc p) fp)
                    (persistent! fp))))]
    (-> fp (get delta) second rest)))

(defn- swap-ops
  [edits]
  (vec (map (fn [op] (case op :+ :- :- :+ op)) edits)))

(defn min+plus->replace
  "A heuristic to create some replacements.
  This one turns isolated consecutive `:-` `:+` into a `:r`,
  do not convert if there's `:-` in front, as it is ambiguous"
  [v]
  (let [n (count v)]
    (loop [r (transient []) i -1 j 0 k 1]
      (let [ei (get v i) ej (get v j) ek (get v k)]
       (cond
         (and (= ej :-)
              (= ek :+)
              (not= ei :-)) (recur (conj! r :r) (+ i 2) (+ j 2) (+ k 2))
         (>= j n)           (persistent! r)
         :else              (recur (conj! r ej) (inc i) (inc j) (inc k)))))))

(defn vec-edits
  [a b]
  (let [n (count a)
        m (count b)
        v (if (< n m)
            (swap-ops (vec-edits* b a m n))
            (vec-edits* a b n m))]
    (-> v vec min+plus->replace)))

(defn- diff-vec
  "Adjust the indices to have a correct editscript"
  [script path a b]
  (reduce
   (fn [{:keys [^long ia ^long ia' ^long ib] :as m} op]
     (case op
       :- (do (diff* script (conj path ia') (get a ia) (e/nada))
              (assoc! m :ia (inc ia)))
       :+ (do (diff* script (conj path ia') (e/nada) (get b ib))
              (assoc! m :ia' (inc ia') :ib (inc ib)))
       :r (do (diff* script (conj path ia') (get a ia) (get b ib))
              (assoc! m :ia (inc ia) :ia' (inc ia') :ib (inc ib)))
       (assoc! m :ia (+ ia ^long op) :ia' (+ ia' ^long op)
               :ib (+ ib ^long op))))
   (transient {:ia 0 :ia' 0 :ib 0})
   (vec-edits a b)))

(defn- diff-set
  [script path a b]
  (doseq [va (set/difference a b)]
    (diff* script (conj path va) va (e/nada)))
  (doseq [vb (set/difference b a)]
    (diff* script (conj path vb) (e/nada) vb)))

(defn- diff-lst
  [script path a b]
  (diff-vec script path (vec a) (vec b)))

(defmacro coll-case
  [a b script path type diff-fn]
  `(case (e/get-type ~b)
     :nil  (e/delete-data ~script ~path)
     ~type (~diff-fn ~script ~path ~a ~b)
     (e/replace-data ~script ~path ~b)))

(defn diff*
  [script path a b]
  (when-not (identical? a b)
    (case (e/get-type a)
      :nil (e/add-data script path b)
      :map (coll-case a b script path :map #'diff-map)
      :vec (coll-case a b script path :vec #'diff-vec)
      :set (coll-case a b script path :set #'diff-set)
      :lst (coll-case a b script path :lst #'diff-lst)
      :val (case (e/get-type b)
             :nil (e/delete-data script path)
             (when-not (= a b)
               (e/replace-data script path b))))))

(defn diff
  "Create an EditScript that represents the difference between `b` and `a`
  This algorithm is fast, but it does not attempt to generate an EditScript
  that is minimal in size"
  [a b]
  (let [script (e/->EditScript [] true 0 0 0 0)]
    (diff* script [] a b)
    script))
