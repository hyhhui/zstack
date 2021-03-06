package org.zstack.test.compute.cluster;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.componentloader.ComponentLoader;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.header.cluster.APIQueryClusterMsg;
import org.zstack.header.cluster.APIQueryClusterReply;
import org.zstack.header.cluster.ClusterInventory;
import org.zstack.test.Api;
import org.zstack.test.ApiSenderException;
import org.zstack.test.DBUtil;
import org.zstack.test.deployer.Deployer;
import org.zstack.test.search.QueryTestValidator;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

public class TestQueryCluster {
    CLogger logger = Utils.getLogger(TestQueryCluster.class);
    Deployer deployer;
    Api api;
    ComponentLoader loader;
    CloudBus bus;
    DatabaseFacade dbf;

    @Before
    public void setUp() throws Exception {
        DBUtil.reDeployDB();
        deployer = new Deployer("deployerXml/cluster/TestQueryCluster.xml");
        deployer.build();
        api = deployer.getApi();
        loader = deployer.getComponentLoader();
        bus = loader.getComponent(CloudBus.class);
        dbf = loader.getComponent(DatabaseFacade.class);
    }
    
    @Test
    public void test() throws InterruptedException,ApiSenderException, JSONException {
        ClusterInventory inv = deployer.clusters.get("Cluster3");
        QueryTestValidator.validateEQ(new APIQueryClusterMsg(), api, APIQueryClusterReply.class, inv);
        QueryTestValidator.validateRandomEQConjunction(new APIQueryClusterMsg(), api, APIQueryClusterReply.class, inv, 2);
    }

}
