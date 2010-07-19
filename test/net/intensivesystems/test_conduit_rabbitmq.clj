(ns net.intensivesystems.test-conduit-rabbitmq
  (:use net.intensivesystems.conduit-rabbitmq :reload-all)
  (:use
     clojure.test
     [net.intensivesystems.conduit :only [conduit conduit-run
                                          new-proc constant-stream]]
     net.intensivesystems.arrows)
  (:import
     [com.rabbitmq.client ConnectionFactory]))

(defn rabbitmq-connection [host vhost user password]
  "Create a simple rabbitmq connection."
  (.newConnection
    (doto (ConnectionFactory.)
      (.setHost host)
      (.setVirtualHost vhost)
      (.setUsername user)
      (.setPassword password))))

(declare *connection*)
(declare *queue*)
(defn rabbitmq-test-fixture [f]
  (with-open [connection (rabbitmq-connection "localhost" "/" "guest" "guest")
              channel (.createChannel connection)]
    (binding [*connection* connection
              *channel* channel
              *exchange* "conduit-exchange"
              *queue* "test-queue"]
      (.exchangeDeclare channel *exchange* "direct")
      (declare-queue *queue*)
      (purge-queue *queue*)
      (f))))

(use-fixtures :each rabbitmq-test-fixture)

(def test-results (atom []))
(def test-rabbit (rabbitmq-arr 'test-queue
                               identity))

(with-arrow conduit
            (deftest test-rabbit-publish-consume
                     (dorun
                       (map (partial publish *queue*)
                            (range 50)))

                     (is (= (range 50)
                            (a-run (a-seq (msg-stream *queue* 100)
                                          (a-arr (fn [m]
                                                   (ack-message m)
                                                   (read-msg m))))))))

            (deftest test-rabbitmq-run
                     (dorun
                       (map (partial publish *queue*)
                            (map (fn [x] [(:id test-rabbit) x])
                                 (range 10))))

                     (is (= (range 10)
                            (conduit-run test-rabbit *queue* *channel* *exchange* 100))))

            (deftest test-new-proc
                     (let [new-rabbit (new-proc test-rabbit
                                                (fn this-fn [n]
                                                  [[(inc n)] this-fn]))]
                       (dorun
                         (map (partial publish *queue*)
                              (map (fn [x] [(:id new-rabbit) x])
                                   (range 10))))


                       (is (= (range 1 11)
                              (conduit-run new-rabbit *queue* *channel* *exchange* 100)))))

                     (deftest test-seq-proc
                              (let [new-rabbit (a-seq (a-arr inc)
                                                      test-rabbit)]
                                (a-run (a-seq (constant-stream (range 10))
                                              new-rabbit))

                                (is (= (range 1 11)
                                       (conduit-run new-rabbit *queue* *channel* *exchange* 100)))))

                     (deftest test-par-proc
                              (let [p1 (rabbitmq-arr *queue* inc)
                                    p2 (rabbitmq-arr *queue* dec)
                                    new-rabbit (a-all p1 p2)
                                    thread-fn (fn [exchange queue]
                                                (with-open [connection (rabbitmq-connection "localhost" "/" "guest" "guest")
                                                            channel (.createChannel connection)]
                                                  (binding [*channel* channel
                                                            *exchange* exchange]
                                                    (let [queue (str queue)]
                                                      (a-run
                                                        (a-seq (msg-stream queue)
                                                               (a-arr (fn [m]
                                                                        [(read-msg m) m]))
                                                               (a-nth 0 (rabbitmq-handler new-rabbit queue))
                                                               (a-nth 1 (a-arr ack-message))))))))

                                    remote-thread (doto (new Thread (partial thread-fn *exchange* *queue*))
                                                    (.start))]

                                (try
                                  (is (= (map vector
                                              (range 1 11)
                                              (range -1 9))
                                         (a-run (a-seq (constant-stream (range 10))
                                                       new-rabbit )))) 
                                  (finally
                                    (Thread/sleep 500)
                                    (.interrupt remote-thread)
                                    (println "waiting...")
                                    (.join remote-thread 5000)
                                    (println "done waiting"))))))

(run-tests)