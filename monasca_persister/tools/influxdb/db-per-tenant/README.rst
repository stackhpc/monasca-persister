migrate-to-db-per-tenant.py
===========================

The general plan for the monasca project is to move in the direction of
having a database per tenant as that will not only give a finer grain
control over retention policy per tenant but also possibly speed up
tenants queries by scoping them within the project.

For further reading - https://storyboard.openstack.org/#!/story/2006331

All effort has been made to ensure this is a safe process. And it
should be safe to run the tool multiple times.  However, it is provided
AS IS and you should use it at your own risk.

Usage
=====

Steps to use this tool:

- Log in to one node where monasca-persister is deployed.

- Identify installation path to monasca-persister.  This may be a
  virtual environment such as
  `/opt/stack/venv/monasca-<version>/lib/python2.7/site-packages/monasca_persister`
  or as in devstack
  `/opt/stack/monasca-persister/monasca_persister/`.

- Identify the existing configuration for monasca-persister. If using a
  java deployment, it may be in
  `/opt/stack/service/monasca/etc/persister-config.yml`
  or in devstack
  `/etc/monasca/persister.conf`

- Invoke the tool to migrate to database per tenant.

::

   sudo -u mon-persister /opt/stack/venv/monasca-<version>/bin/python migrate-to-db-per-tenant.py --config-file /etc/monasca/persister.conf


License
=======

Copyright (c) 2019 StackHPC Limited

Licensed under the Apache License, Version 2.0 (the “License”); you may
not use this file except in compliance with the License. You may obtain
a copy of the License at

::

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an “AS IS” BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
