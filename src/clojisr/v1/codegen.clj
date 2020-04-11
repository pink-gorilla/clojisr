(ns clojisr.v1.codegen
  (:require
   [clojisr.v1.robject :refer [instance-robject?]]
   [clojisr.v1.using-sessions :as using-sessions]
   [clojure.string :refer [join]]
   [clojisr.v1.protocols :as prot]
   [clojisr.v1.util :refer [bracket-data]])
  (:import [clojure.lang Named]))

;; helpers

;; Convert instant to date/time R string
(defonce ^:private dt-format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss"))

;; Add context to a call, used to change formatting behaviour in certain cases
(defmacro ^:private with-ctx
  [c & r]
  `(let [~'ctx (conj (or ~'ctx #{}) ~@c)] ~@r))

;; Leave nil untouched when coercing seq
(defn ^:private map-with-nil
  [f xs]
  (map #(some-> % f) xs))

;; all binary operators as set of strings
(def binary-operators #{"$" "=" "<<-" "<-" "+" "-" "/" "*" "&" "&&" "|" "||" "==" "!=" "<=" ">=" "<" ">" "%in%" "%%" "**"})
(def binary-operators-flat #{"=" "<<-" "<-" "$"})
(def unary-operators #{"+" "-"})
(def wrapped-operators #{"+" "-" "/" "*" "&" "&&" "|" "||" "==" "!=" "<=" ">=" "<" ">"})

(defn form->java->code
  "Convert form using Java backend.
   
   Used when seq or map is too big for string.

   Returns R handler name"
  [value session]
  (-> value
      (->> (prot/clj->java session))
      (using-sessions/java->r session)
      :object-name))

(declare form->code)

(defn named-or-anything->string
  "Convert any named or any other object to a string."
  [s]
  (if (instance? Named s)
    (name s)
    (str s)))

(defn args->code
  "Convert arguments to a partially named list.

  Used to format function arguments or when coercing vector to a list."
  ([args session ctx] (args->code args session ctx nil))
  ([args session ctx bra?]
   (->> (loop [res []
               [fa & ra :as all] args]
          (if (seq all)
            (if (keyword? fa)
              (recur (conj res (format "%s=%s" (name fa) (form->code (first ra) session ctx))) (rest ra))
              (recur (conj res (if (and bra? (nil? fa)) "" (form->code fa session ctx))) ra))
            res))
        (join ","))))

(defn function-call->code
  "Create R function call."
  [f args session ctx]
  (format "%s(%s)" f (args->code args session ctx)))

(defn binary-call->code
  "Create R function call for binary operator.

  Two possible strings can be returned depending on context: with parentheses or without."
  [f [f1 & fr] session ctx]
  (let [fmt (partial format (if (ctx :flat) "%s%s%s" "(%s%s%s)"))
        res (if-not f1
              (throw (Exception. "Positive number of arguments is required."))
              (let [f1-code (form->code f1 session ctx)]
                (if-not fr
                  (if (unary-operators f)
                    (str f f1-code)
                    f1-code)
                  (reduce (fn [a1 a2] (fmt a1 f (form->code a2 session ctx))) f1-code fr))))]
    (if (and (wrapped-operators f)
             (not (ctx :unwrap))
             (ctx :flat))
      (format "(%s)" res)
      res)))

(defn formula->code
  "Create formula string.

  Two arguments are expected, each for each side (left and right). If you want to skip given side, use `nil`.

  Formulas are treated as binary call and are formated without parentheses (context = `:flat`)."
  [[lf rf] session ctx]
  (with-ctx [:unwrap :flat]
    (let [lf (if (nil? lf) "" (form->code lf session ctx))
          rf (if (nil? rf) "" (form->code rf session ctx))]
      (format "(%s~%s)" lf rf))))

(defn symbol-form->code
  "Create binary or regular function call, when first argument in a seq was a symbol."
  [f args session ctx]
  (if (binary-operators f)
    (if (binary-operators-flat f)
      (with-ctx [:flat] (binary-call->code f args session ctx))
      (binary-call->code f args session ctx))
    (function-call->code f args session ctx)))

(defn function-def->code
  "Create R function definition.

  Arguments can be a partially named list."
  [args body session ctx]
  (format "function(%s) {%s}"
          (args->code args session ctx)
          (join ";" (map #(form->code % session ctx) body))))

(defn ifelse->code
  "Create if or if-else form"
  [vs session ctx]
  (let [[pred f1 f2] (map #(form->code % session ctx) (take 3 vs))]
    (if f2
      (format "if(%s) {%s} else {%s}"  pred f1 f2)
      (format "if(%s) {%s}" pred f1))))

(defn for-loop->code
  "Create for loop"
  [bindings body session ctx]
  (if (seq bindings)
    (let [[v s & r] bindings]
      (format "for(%s in %s){%s\n}"
              (name v) (form->code s session ctx)
              (for-loop->code r body session ctx)))
    (join ";" (map #(form->code % session ctx) body))))

(defn while-loop->code
  "Create while loop"
  [pred body session ctx]
  (format "while(%s) {%s}"
          (form->code pred session ctx)
          (join ";" (map #(form->code % session ctx) body))))

(defn colon->code
  "Create colon"
  [[a b] session ctx]
  (format "%s:%s" (form->code a session ctx) (form->code b session ctx)))

(defn bracket-call->code
  [[bra all?] args session ctx]
  (let [args (if (and (not all?)
                      (nil? (last args)))
               (conj (vec (butlast args)) 'NULL)
               args)]
    (format "%s(%s)" bra (args->code args session ctx true))))

(defn unquote-form->code
  "Eval unquoted form.

  Used when unquote symbol is part of regular quoted form.

  Warning: You can't unquote local bindings! For this case use inside syntax quote."
  [u-form session ctx]
  (-> (apply eval u-form)
      (form->code session ctx)))

(declare seq-form->code)

(defn vector->code
  "Construct R vector using `c` function.

  When first element is a coersion symbol starting with `:!`, values are coerced to the required type.
  When number of elements is big enough, java backend is used to transfer data first.

  `nil` is converted to `NA`"
  [[f & r :as v-form] session ctx]
  (with-ctx [:na]
    (case f
      :!string (vector->code (map-with-nil named-or-anything->string r) session ctx)
      :!boolean (vector->code (map-with-nil #(if % true false) r) session ctx)
      :!int (vector->code (map-with-nil unchecked-int r) session ctx)
      :!double (vector->code (map-with-nil unchecked-double r) session ctx)
      :!named (format "c(%s)" (args->code r session ctx))
      :!list (format "list(%s)" (args->code r session ctx))
      :!factor (format "factor(%s)" (vector->code r session ctx))
      :!ct (format "as.POSIXct(%s)" (vector->code r session ctx))
      :!lt (format "as.POSIXlt(%s)" (vector->code r session ctx))
      :!call (seq-form->code r session ctx)
      (if (< (count v-form) 80)
        (format "c(%s)" (join "," (map #(form->code % session ctx) v-form)))
        (form->java->code v-form session)))))

(defn seq-form->code
  "Process sequence.

  Possible paths are possible if given first element is:

  * one of the symbols: `function` or `formula` - create fn definition or formula
  * ~ (unquote) - eval rest of the form
  * RObject which is a function - create function call
  * sequence - create function call with processed first element.
  * any symbol - function call
  * one of the functions with special names: [,[[,[<-,[[<-,:
  * any other value - construct vector"
  [[f & r :as seq-form] session ctx]
  (if (symbol? f)
    (let [fs (name f)]
      (cond
        (= "colon" fs) (colon->code r session ctx)
        (= "function" fs) (function-def->code (first r) (rest r) session ctx)
        (or (= "tilde" fs)
            (= "formula" fs)) (formula->code r session ctx)
        (= "if" fs) (ifelse->code r session ctx)
        (= "do" fs) (join ";" (map #(form->code % session ctx) r))
        (= "for" fs) (for-loop->code (first r) (rest r) session ctx)
        (= "while" fs) (while-loop->code (first r) (rest r) session ctx)
        (contains? bracket-data fs) (bracket-call->code (bracket-data fs) r session ctx)
        (= 'clojure.core/unquote f) (unquote-form->code r session ctx)
        :else (symbol-form->code fs r session ctx)))
    (cond
      (using-sessions/function? f) (function-call->code (:object-name f) r session ctx)
      (string? f) (if (and (= (first f) \`)
                           (= (last f) \`))
                    (function-call->code f r session ctx)
                    (vector->code seq-form session ctx))
      (sequential? f) (function-call->code (seq-form->code f session ctx) r session ctx)
      :else (vector->code seq-form session ctx))))

(defn map->code
  "Convert map to a named list.

  For big maps, Java backend is used to transfer data.

  `nil` is treated as `NA`"
  [form session ctx]
  (if (< (count form) 50)
    (with-ctx [:na]
      (->> (map (fn [[k v]]
                  (format "%s=%s" (form->code k session ctx) (form->code v session ctx))) form)
           (join ",")
           (format "list(%s)")))
    (form->java->code form session)))

(defn nil->code
  "Convert `nil` to `NA` or `NULL` (based on context)"
  [ctx]
  (cond
    (ctx :nil) nil
    (ctx :na) "NA"
    :else "NULL"))

(defn form->code
  "Format every possible form to a R string."
  ([form session] (form->code form session #{}))
  ([form session ctx]
   (cond
     (vector? form) (vector->code form session ctx) ;; vector always is converted to datatype
     (sequential? form) (seq-form->code form session ctx) ;; sequence is usually call
     (instance-robject? form) (:object-name form) ;; RObject is a R symbol
     (map? form) (map->code form session ctx) ;; map goes to a list
     (string? form) (format "\"%s\"" form) ;; string is string wrapped in double quotes
     (integer? form) (str form) ;; int is treated literally
     (rational? form) (format "(%s)" form) ;; rational is wrapped in in case of used in calculations
     (number? form) (str form) ;; other numbers are treated literally
     (boolean? form) (if form "TRUE" "FALSE") ;; boolean as logical
     (nil? form) (nil->code ctx)
     (inst? form) (format "'%s'" (.format dt-format form)) ;; date/time just as string, to be converted to time by the user
     (instance? Named form) (name form)
     :else (form->java->code form session))))
