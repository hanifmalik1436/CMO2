package com.cmo2.integrations.core;

import com.cmo2.integrations.core.SyncModels.DeployRequest;
import com.cmo2.integrations.core.SyncModels.DeployResult;
import com.cmo2.integrations.core.SyncModels.SyncRequest;
import com.cmo2.integrations.core.SyncModels.SyncResult;

/**
 * GoHighLevel is the only implementation now — every other marketing platform is connected
 * inside the client's own GHL sub-account instead of through a direct CMO2 connector (the
 * 13 former expansion connectors were retired with the agency/sub-account pivot).
 */
public interface PlatformConnector {

    Platform platform();

    /** Verify the credentials are valid and the platform is reachable. */
    ConnectionHealth testConnection(PlatformCredentials credentials);

    /** Pull contacts/campaigns/KPIs for a company. */
    SyncResult pullData(SyncRequest request);

    /** Push generated content to the platform. */
    DeployResult deployAsset(DeployRequest request);

    /** Whether this platform accepts deployed assets (false for pure data sources like payments). */
    default boolean deploysAssets() {
        return true;
    }
}
