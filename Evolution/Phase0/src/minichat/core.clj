(ns minichat.core
  (:require [jsonista.core :as j]
            [babashka.http-client :as http]))

(def api-url "https://api.openai.com/v1/chat/completions")
(def model  "gpt-4o-mini")
(def system "You are a concise, helpful assistant. Keep replies under two sentences unless asked.")

(def mapper
  ;; Keywordize incoming keys; stringify keys on write.
  (j/object-mapper {:encode-key-fn name
                    :decode-key-fn keyword}))

(defn ^String api-key []
      (or (System/getenv "OPENAI_API_KEY")
          (throw (ex-info "OPENAI_API_KEY is not set" {}))))

(defn redact-headers
      "Redact sensitive headers for logging."
      [headers]
      (cond-> headers
              (contains? headers "Authorization")
              (assoc "Authorization" "Bearer ***")))

(defn log-http
      "Log a request/response pair to the CLI.
       Expects:
       - req: {:method \"POST\" :url api-url :headers {...} :body \"json-string\"}
       - res: {:status int :headers {...} :body \"json-string\"}"
      [req res]
      (println "\n--- HTTP Request ---")
      (println (:method req) (:url req))
      (println "Headers:" (redact-headers (:headers req)))
      (println "Body:" (:body req))
      (println "--- HTTP Response ---")
      (println "Status:" (:status res))
      (println "Headers:" (:headers res))
      (println "Body:" (:body res))
      (println "---------------------"))

(defn chat-once
      "Send message history to OpenAI; return the assistant's reply."
      [history]
      (let [payload {:model model :messages history}
            body    (j/write-value-as-string payload mapper)
            req     {:method "POST"
                     :url api-url
                     :headers {"Content-Type" "application/json"
                               "Authorization" (str "Bearer " (api-key))}
                     :body body}
            res     (http/post api-url {:headers (:headers req)
                                        :body    (:body req)})]
           (log-http req res)
           (let [{:keys [status body]} res]
                (when-not (<= 200 status 299)
                          (throw (ex-info "OpenAI error" {:status status :body body})))
                (-> (j/read-value body mapper)
                    (get-in [:choices 0 :message :content])))))

(defn -main [& _]
      (println "Minimal OpenAI chat demo. Type 'exit' to quit.\n")
      (loop [history [{:role "system" :content system}]]
            (print "you> ") (flush)
            (when-let [line (read-line)]
                      (if (#{"exit" "quit"} line)
                        (println "bye.")
                        (let [history'  (conj history {:role "user" :content line})
                              reply     (try (chat-once history')
                                             (catch Exception e
                                               (str "Error: " (.getMessage e))))
                              history'' (conj history' {:role "assistant" :content reply})]
                             (println "ai >" reply)
                             (recur history''))))))
