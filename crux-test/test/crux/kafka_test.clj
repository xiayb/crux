(ns crux.kafka-test
  (:require [clojure.test :as t]
            [crux.api :as api]
            [crux.codec :as c]
            [crux.db :as db]
            [crux.fixtures.api :as fapi :refer [*api* *opts*]]
            [crux.fixtures.kafka :as fk]
            [crux.fixtures.kv :as kvf]
            [crux.kafka :as k]
            [crux.kafka.consumer :as kc]
            [crux.tx.consumer :as tc]
            [crux.rdf :as rdf]
            [crux.tx :as tx])
  (:import java.time.Duration
           [org.apache.kafka.clients.consumer ConsumerRecord KafkaConsumer]
           org.apache.kafka.clients.producer.ProducerRecord))

(t/use-fixtures :once fk/with-embedded-kafka-cluster)
(t/use-fixtures :each fk/with-cluster-node-opts kvf/with-kv-dir)

(defn- poll-topic [offsets topic]
  (with-open [tx-consumer ^KafkaConsumer (fk/with-consumer)]
    (let [tx-offsets (tc/map->IndexedOffsets {:indexer (:indexer *api*) :k offsets})]
      (kc/subscribe-from-stored-offsets tx-offsets tx-consumer [topic]))
    (doall (map (juxt #(.key ^ConsumerRecord %) #(.value ^ConsumerRecord %))
                (.poll tx-consumer (Duration/ofMillis 10000))))))

(defn- txes-on-topic []
  (poll-topic ::txes (:crux.kafka/tx-topic *opts*)))

(defn- docs-on-topic [t]
  (poll-topic ::docs t))

(t/deftest test-can-transact-entities
  (fapi/with-node
    (fn []
      (let [tx-ops (rdf/->tx-ops (rdf/ntriples "crux/example-data-artists.nt"))
            submitted-tx (.submitTx *api* tx-ops)
            _ (.awaitTx *api* submitted-tx nil)]

        (t/testing "tx-log contains relevant txes"
          (let [txes (txes-on-topic)]
            (t/is (= 7 (count (second (first txes)))))))

        (t/testing "doc-log contains relevant docs"
          (let [docs (docs-on-topic (:crux.kafka/doc-topic *opts*))]
            (t/is (= (rdf/with-prefix {:foaf "http://xmlns.com/foaf/0.1/"}
                       {:foaf/firstName "Pablo"
                        :foaf/surname "Picasso"})
                     (select-keys (second (first docs))
                                  (rdf/with-prefix {:foaf "http://xmlns.com/foaf/0.1/"}
                                    [:foaf/firstName
                                     :foaf/surname]))))))))))

(t/deftest test-can-transact-and-query-entities
  (fapi/with-node
    (fn []
      (let [tx-ops (rdf/->tx-ops (rdf/ntriples "crux/picasso.nt"))
            {:crux.tx/keys [tx-time tx-id] :as submitted-tx} (.submitTx *api* tx-ops)
            _ (.awaitTx *api* submitted-tx (Duration/ofSeconds 20))]

        (t/testing "transacting and indexing"
          (t/is (= 3 (count (docs-on-topic (:crux.kafka/doc-topic *opts*)))))
          (t/is (= 1 (count (txes-on-topic)))))

        (t/testing "querying transacted data"
          (t/is (= #{[:http://example.org/Picasso]}
                   (api/q (api/db *api*)
                        (rdf/with-prefix {:foaf "http://xmlns.com/foaf/0.1/"}
                          '{:find [e]
                            :where [[e :foaf/firstName "Pablo"]]})))))

        (t/testing "can read tx log"
          (t/testing "tx-log"
            (with-open [tx-log-iterator (.openTxLog *api* nil false)]
              (let [result (iterator-seq tx-log-iterator)]
                (t/is (not (realized? result)))
                (t/is (= {:crux.tx/tx-time tx-time
                          :crux.tx/tx-id tx-id}
                         (dissoc (first result) :crux.tx.event/tx-events)))
                (t/is (= 1 (count result)))
                (t/is (= 3 (count (:crux.tx.event/tx-events (first result)))))
                (t/is (realized? result))))))

        (t/testing "new node can pick-up"
          (fapi/with-opts {:crux.kafka/group-id "different-test-group"}
            (fn []
              (kvf/with-kv-dir
                (fn []
                  (fapi/with-node
                    (fn []
                      (.awaitTx *api* submitted-tx (Duration/ofSeconds 20))
                      (t/is (= #{[:http://example.org/Picasso]}
                               (api/q (api/db *api*)
                                    (rdf/with-prefix {:foaf "http://xmlns.com/foaf/0.1/"}
                                      '{:find [e]
                                        :where [[e :foaf/firstName "Pablo"]]}))))))))))

          (t/testing "no new txes or docs"
            (t/is (= 3 (count (docs-on-topic (:crux.kafka/doc-topic *opts*)))))
            (t/is (= 1 (count (txes-on-topic))))))))))

(defn- compact-to-topic [topic docs]
  (k/create-topic fk/*admin-client* topic 1 1 {})

  (with-open [producer (fk/with-producer)]
    (doseq [[k v] (->> docs
                       (reverse)
                       (reduce (fn [docs doc]
                                 (if (contains? (set (map (comp str first) docs)) (str (first doc)))
                                   docs
                                   (conj docs doc))) [])
                       reverse)]
      @(.send producer (ProducerRecord. topic k v)))))

(t/deftest test-can-process-compacted-documents
  ;; when doing a evict a tombstone document will be written to
  ;; replace the original document. The original document will be then
  ;; removed once kafka compacts it away.
  (fapi/with-node
    (fn []
      (t/testing "transacting and indexing"
        (let [evicted-doc {:crux.db/id :to-be-evicted :personal "private"}
              non-evicted-doc {:crux.db/id :not-evicted :personal "private"}
              after-evict-doc {:crux.db/id :after-evict :personal "private"}

              {:crux.tx/keys [tx-time tx-id] :as submitted-tx} (.submitTx *api* [[:crux.tx/put evicted-doc]
                                                                                 [:crux.tx/put non-evicted-doc]])
              _ (.awaitTx *api* submitted-tx nil)

              evicted-doc-hash (:crux.db/content-hash (api/entity-tx (api/db *api*) (:crux.db/id evicted-doc)))

              {:crux.tx/keys [tx-time tx-id] :as submitted-tx} (.submitTx *api* [[:crux.tx/evict (:crux.db/id evicted-doc)]])
              _ (.awaitTx *api* submitted-tx nil)

              {:crux.tx/keys [tx-time tx-id] :as submitted-tx} (.submitTx *api* [[:crux.tx/put after-evict-doc]])
              _ (.awaitTx *api* submitted-tx nil)]

          (t/testing "querying transacted data"
            (t/is (= non-evicted-doc (api/entity (api/db *api*) (:crux.db/id non-evicted-doc))))
            (t/is (nil? (api/entity (api/db *api*) (:crux.db/id evicted-doc))))
            (t/is (= after-evict-doc (api/entity (api/db *api*) (:crux.db/id after-evict-doc)))))

          (t/testing "compaction"
            (compact-to-topic "compacted-doc-topic" (docs-on-topic (:crux.kafka/doc-topic *opts*)))
            (assert (= #{{:crux.db/id :not-evicted, :personal "private"}
                         {:crux.db/id (c/new-id :to-be-evicted) :crux.db/evicted? true}
                         {:crux.db/id :after-evict, :personal "private"}}
                       (set (map second (docs-on-topic "compacted-doc-topic")))))

            (t/testing "new node can pick-up"
              (fapi/with-opts {:crux.node/topology ['crux.kafka/topology 'crux.kv.memdb/kv-store]
                               :crux.kafka/doc-topic "compacted-doc-topic"
                               :crux.kafka/group-id "different-test-group"}
                (fn []
                  (kvf/with-kv-dir
                    (fn []
                      (fapi/with-node
                        (fn []
                          (.awaitTx *api* submitted-tx nil)
                          (t/testing "querying transacted data"
                            (t/is (= non-evicted-doc (api/entity (api/db *api*) (:crux.db/id non-evicted-doc))))
                            (t/is (nil? (api/entity (api/db *api*) (:crux.db/id evicted-doc))))
                            (t/is (= after-evict-doc (api/entity (api/db *api*) (:crux.db/id after-evict-doc)))))))))))

              (t/testing "no new txes or docs"
                (t/is (= 3 (count (docs-on-topic "compacted-doc-topic"))))
                (t/is (= 3 (count (txes-on-topic))))))))))))
