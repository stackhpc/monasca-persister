# (C) Copyright 2016 Hewlett Packard Enterprise Development Company LP
# (C) Copyright 2017 SUSE LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied.
# See the License for the specific language governing permissions and
# limitations under the License.
import os

from oslo_config import cfg
from oslo_log import log

from monasca_common.kafka import consumer

LOG = log.getLogger(__name__)


class Persister(object):

    def __init__(self, kafka_conf, zookeeper_conf, repository):

        self._data_points = {}
        self._n_data_points = 0

        self._kafka_topic = kafka_conf.topic

        self._batch_size = kafka_conf.batch_size

        self._consumer = consumer.KafkaConsumer(
            kafka_conf.uri,
            zookeeper_conf.uri,
            kafka_conf.zookeeper_path,
            kafka_conf.group_id,
            kafka_conf.topic,
            repartition_callback=self._flush,
            commit_callback=self._flush,
            commit_timeout=kafka_conf.max_wait_time_seconds)

        self.repository = repository()

    def _flush(self):
        if not self._data_points:
            return

        try:
            for tenant_id, data_points in self._data_points.iteritems():
                self.repository.write_batch(data_points, tenant_id)

            LOG.info("Processed {} messages from topic '{}'".format(
                self._n_data_points, self._kafka_topic))

            self._data_points = {}
            self._n_data_points = 0
            self._consumer.commit()
        except Exception as ex:
            if "partial write: points beyond retention policy dropped" in ex.message:
                LOG.warning("Some points older than retention policy were dropped")
                self._data_points = {}
                self._n_data_points = 0
                self._consumer.commit()

            elif cfg.CONF.repositories.ignore_parse_point_error \
                    and "unable to parse" in ex.message:
                LOG.warning("Some points were unable to be parsed and were dropped")
                self._data_points = {}
                self._n_data_points = 0
                self._consumer.commit()

            else:
                LOG.exception("Error writing to database: {}"
                              .format(self._data_points))
                raise ex

    def run(self):
        try:
            for raw_message in self._consumer:
                try:
                    message = raw_message[1]
                    data_point, tenant_id = self.repository.process_message(message)
                    self._data_points.setdefault(tenant_id, []).append(data_point)
                    self._n_data_points += 1
                except Exception:
                    LOG.exception('Error processing message. Message is '
                                  'being dropped. {}'.format(message))

                if self._n_data_points >= self._batch_size:
                    self._flush()
        except Exception:
            LOG.exception(
                'Persister encountered fatal exception processing '
                'messages. '
                'Shutting down all threads and exiting')
            os._exit(1)
