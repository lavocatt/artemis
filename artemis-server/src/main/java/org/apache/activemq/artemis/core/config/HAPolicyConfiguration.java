/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.core.config;

import java.io.Serializable;

import org.apache.activemq.artemis.api.config.annotation.Discriminator;
import org.apache.activemq.artemis.core.config.ha.ColocatedPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.PrimaryOnlyPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.ReplicaPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.ReplicatedPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.ReplicationBackupPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.ReplicationPrimaryPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.SharedStoreBackupPolicyConfiguration;
import org.apache.activemq.artemis.core.config.ha.SharedStorePrimaryPolicyConfiguration;

@Discriminator(field = "type", value = {
   @Discriminator.Mapping(subtype = PrimaryOnlyPolicyConfiguration.class, name = "PRIMARY_ONLY"),
   @Discriminator.Mapping(subtype = ReplicatedPolicyConfiguration.class, name = "REPLICATION_PRIMARY_QUORUM_VOTING"),
   @Discriminator.Mapping(subtype = ReplicaPolicyConfiguration.class, name = "REPLICATION_BACKUP_QUORUM_VOTING"),
   @Discriminator.Mapping(subtype = SharedStorePrimaryPolicyConfiguration.class, name = "SHARED_STORE_PRIMARY"),
   @Discriminator.Mapping(subtype = SharedStoreBackupPolicyConfiguration.class, name = "SHARED_STORE_BACKUP"),
   @Discriminator.Mapping(subtype = ColocatedPolicyConfiguration.class, name = "COLOCATED"),
   @Discriminator.Mapping(subtype = ReplicationPrimaryPolicyConfiguration.class, name = "REPLICATION_PRIMARY_LOCK_MANAGER"),
   @Discriminator.Mapping(subtype = ReplicationBackupPolicyConfiguration.class, name = "REPLICATION_BACKUP_LOCK_MANAGER")
})
public interface HAPolicyConfiguration extends Serializable {

   enum TYPE {
      PRIMARY_ONLY("Primary Only"),
      REPLICATION_PRIMARY_QUORUM_VOTING("Replication Primary w/quorum voting"),
      REPLICATION_BACKUP_QUORUM_VOTING("Replication Backup w/quorum voting"),
      SHARED_STORE_PRIMARY("Shared Store Primary"),
      SHARED_STORE_BACKUP("Shared Store Backup"),
      COLOCATED("Colocated"),
      REPLICATION_PRIMARY_LOCK_MANAGER("Replication Primary w/lock manager"),
      REPLICATION_BACKUP_LOCK_MANAGER("Replication Backup w/lock manager");

      private String name;

      TYPE(String name) {
         this.name = name;
      }

      public String getName() {
         return name;
      }
   }

   TYPE getType();
}
