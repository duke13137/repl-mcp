(ns is.simm.repl-mcp.integration.sse-direct-test
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.server :as server]
            [is.simm.repl-mcp.tools :as tools]
            [is.simm.repl-mcp.transport.sse :as sse]
            [jsonista.core :as json]))

(deftest direct-sse-test
  "Test legacy SSE endpoints directly."
  (testing "Direct server creation and HTTP test"
    (let [instance-factory (fn []
                             (server/create-mcp-server-instance!
                              {:tools (tools/get-tool-definitions)
                               :server-info {:name "direct-sse-server" :version "1.0.0"}}))
          test-port 18491
          http-server (sse/start-http-server! instance-factory test-port)]
      (try
        (Thread/sleep 2000)
        (let [response (http/get (str "http://127.0.0.1:" test-port "/sse")
                                 {:timeout 5000
                                  :throw false
                                  :as :stream})]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (with-open [reader (java.io.BufferedReader.
                              (java.io.InputStreamReader. (:body response)))]
            (let [session-id (loop [lines-read 0]
                               (when (< lines-read 10)
                                 (when-let [line (.readLine reader)]
                                   (cond
                                     (.startsWith line "event: endpoint")
                                     (let [data-line (.readLine reader)]
                                       (when (.startsWith data-line "data: ")
                                         (let [endpoint (subs data-line 6)]
                                           (last (str/split endpoint #"/")))))

                                     :else
                                     (recur (inc lines-read))))))]
              (is (string? session-id))
              (is (not (empty? session-id)))
              (when session-id
                (let [message-url (str "http://127.0.0.1:" test-port "/messages/" session-id)
                      init-msg {:jsonrpc "2.0"
                                :method "initialize"
                                :params {:clientInfo {:name "test-client" :version "1.0.0"}
                                         :protocolVersion "2025-03-26"
                                         :capabilities {}}
                                :id 1}
                      response (http/post message-url
                                          {:headers {"Content-Type" "application/json"}
                                           :body (json/write-value-as-string init-msg)
                                           :timeout 3000
                                           :throw false})]
                  (is (= 202 (:status response)))
                  (is (= "Accepted" (:body response))))))))
        (finally
          (sse/stop-http-server! http-server))))))
