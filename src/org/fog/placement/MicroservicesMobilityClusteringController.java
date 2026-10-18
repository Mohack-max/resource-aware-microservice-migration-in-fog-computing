package org.fog.placement;

import org.apache.commons.math3.util.Pair;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.AppEdge;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.entities.MicroserviceFogDevice;
import org.fog.entities.PlacementRequest;
import org.fog.mobilitydata.References;
import org.fog.utils.Config;
import org.fog.utils.FogEvents;
import org.fog.utils.MigrationDelayMonitor;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by Samodha Pallewatta on 7/31/2020.
 */
public class MicroservicesMobilityClusteringController extends MicroservicesController {

    private LocationHandler locator;
    private Map<Integer, Integer> parentReference;

    // ---- migration policy hook (research extension) ----
    private static MigrationPolicy migrationPolicy = new MigrationPolicy.Always(); // default = original iFogSim2 behaviour
    public static int migrationsPerformed = 0, migrationsSkipped = 0;
    public static long decisionNanos = 0;

    public static void setMigrationPolicy(MigrationPolicy p) { migrationPolicy = p; }


    protected Map<Integer, Map<String, PlacementRequest>> perClientDevicePrs = new HashMap<>();  // clientDevice -> <Application -> PR>

    /**
     * @param name
     * @param fogDevices
     * @param sensors
     * @param applications
     */
    public MicroservicesMobilityClusteringController(String name, List<FogDevice> fogDevices, List<Sensor> sensors, List<Application> applications, List<Integer> clusterLevel, Double clusterLatency, int placementLogic, LocationHandler locator) {
        super(name, fogDevices, sensors, applications, clusterLevel, clusterLatency, placementLogic);

        setLocator(locator);
        setParentReference(new HashMap<Integer, Integer>());

        super.init();

    }

    public MicroservicesMobilityClusteringController(String name, List<FogDevice> fogDevices, List<Sensor> sensors, List<Application> applications, List<Integer> clusterLevel, Double clusterLatency, int placementLogic, Map<Integer, List<FogDevice>> monitored, LocationHandler locator) {

        super(name, fogDevices, sensors, applications, clusterLevel, clusterLatency, placementLogic, monitored);

        setLocator(locator);
        setParentReference(new HashMap<Integer, Integer>());

        super.init(monitored);
    }

    @Override
    protected void init() {
        // kept empty as locator should be set before init functions.
    }

    @Override
    protected void init(Map<Integer, List<FogDevice>> monitored) {
        // kept empty as locator should be set before init functions.
    }

    private void setParentReference(HashMap<Integer, Integer> parentReference) {
        // TODO Auto-generated method stub
        this.parentReference = parentReference;
    }

    @Override
    public void startEntity() {
        if (Config.ENABLE_DYNAMIC_CLUSTERING)
            clusteringSubmit(clustering_levels);

        super.startEntity();

        sendNow(getId(), FogEvents.MOBILITY_SUBMIT);
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case FogEvents.MOBILITY_SUBMIT:
                processMobilityData();
                break;
            case FogEvents.MOBILITY_MANAGEMENT:
                processMobility(ev);
                break;
            case FogEvents.STOP_SIMULATION:
                CloudSim.stopSimulation();
                printTimeDetails();
                printPowerDetails();
                printCostDetails();
                printNetworkUsageDetails();
                printMigrationDelayDetails();
                System.out.println("POLICY = " + migrationPolicy.name());
                System.out.println("Migrations performed = " + migrationsPerformed);
                System.out.println("Migrations skipped = " + migrationsSkipped);
                System.out.println("Decision runtime (ms) = " + decisionNanos / 1e6);
                if (migrationPolicy instanceof QLearningMigrationPolicy)
                    ((QLearningMigrationPolicy) migrationPolicy).save();
                System.exit(0);
                break;
            default:
                super.processEvent(ev);
                break;
        }
    }

    private void printMigrationDelayDetails() {
        // TODO Auto-generated method stub
        System.out.println("Total time required for module migration = " + MigrationDelayMonitor.getMigrationDelay());
    }

    @Override
    public void submitPlacementRequests(List<PlacementRequest> placementRequests, int delay) {
        for (PlacementRequest p : placementRequests) {
            placementRequestDelayMap.put(p, delay);

            int clientDeviceId = p.getGatewayDeviceId();
            String app = p.getApplicationId();
            if (perClientDevicePrs.containsKey(clientDeviceId)) {
                perClientDevicePrs.get(clientDeviceId).put(app, p);
            } else {
                Map<String, PlacementRequest> map = new HashMap<>();
                map.put(app, p);
                perClientDevicePrs.put(clientDeviceId, map);
            }
        }
    }

    @Override
    protected void connectWithLatencies() {
        for (String dataId : locator.getDataIdsLevelReferences().keySet()) {
            for (int instenceId : locator.getInstenceDataIdReferences().keySet()) {
                if (locator.getInstenceDataIdReferences().get(instenceId).equals(dataId)) {
                    FogDevice fogDevice = getFogDeviceById(instenceId);
                    if (locator.getDataIdsLevelReferences().get(dataId) == locator.getLevelID("User") && fogDevice.getParentId() == References.NOT_SET) {
                        int parentID = locator.determineParent(fogDevice.getId(), References.INIT_TIME);
                        parentReference.put(fogDevice.getId(), parentID);
                        fogDevice.setParentId(parentID);
                    } else
                        parentReference.put(fogDevice.getId(), fogDevice.getParentId());
                }
            }
        }


        FogDevice cloud = getCloud();
        parentReference.put(cloud.getId(), cloud.getParentId());

        for (FogDevice fogDevice : fogDevices) {
            FogDevice parent = getFogDeviceById(parentReference.get(fogDevice.getId()));
            if (parent == null)
                continue;
            double latency = fogDevice.getUplinkLatency();
            parent.getChildToLatencyMap().put(fogDevice.getId(), latency);
            parent.getChildrenIds().add(fogDevice.getId());
            System.out.println("Child " + fogDevice.getName() + "\t----->\tParent " + parent.getName());
        }
    }

    private void processMobility(SimEvent ev) {

        // TODO Auto-generated method stub
        FogDevice fogDevice = (FogDevice) ev.getData();
        FogDevice prevParent = getFogDeviceById(parentReference.get(fogDevice.getId()));
        FogDevice newParent = getFogDeviceById(locator.determineParent(fogDevice.getId(), CloudSim.clock()));
        System.out.println(CloudSim.clock() + " Starting Mobility Management for " + fogDevice.getName());
        parentReference.put(fogDevice.getId(), newParent.getId());
        Map<String, Integer> migratingModules = new HashMap<>(); // migrating module _> it's device (can be preParent or  device the same cluster
        setNewOrchestratorNode(fogDevice,newParent);

        if (prevParent.getId() != newParent.getId()) {
            //printFogDeviceChildren(newParent.getId());
            //printFogDeviceChildren(prevParent.getId());

            //common ancestor policy
            List<Integer> newParentPath = getPathsToCloud(newParent.getId());
            List<Integer> prevParentPath = getPathsToCloud(prevParent.getId());
            int commonAncestor = determineAncestor(newParentPath, prevParentPath);


            fogDevice.setParentId(newParent.getId());
            System.out.println("Child " + fogDevice.getName() + "\t----->\tParent " + newParent.getName());
            newParent.getChildToLatencyMap().put(fogDevice.getId(), fogDevice.getUplinkLatency());
            newParent.addChild(fogDevice.getId());
            prevParent.removeChild(fogDevice.getId());

            for (String applicationName : fogDevice.getActiveApplications()) {

                Map<String, Integer> candidates = migrationPolicy.reconsiderLeftBehind()
                        ? getAllMovableModules(fogDevice, newParent, applicationName)
                        : getModulesToMigrate(fogDevice, commonAncestor, applicationName);
                migratingModules = filterByPolicy(fogDevice, newParent, applicationName, candidates);
                HashMap<String, Double> upDelays = new HashMap<>(); // per migrating module
                HashMap<String, Double> downDelays = new HashMap<>(); // per migrating module

                // Destination selection: which node the migrating modules actually go to. Defaults to
                // newParent (original iFogSim2 behaviour, unchanged for the always/never/cost policies).
                // For the learned policy, a cluster-scoped destination search additionally considers
                // newParent's cluster peers and picks whichever the agent's learned values rate best,
                // summed over the modules already decided (above) to migrate.
                FogDevice destination = newParent;
                if (migrationPolicy instanceof QLearningMigrationPolicy && !migratingModules.isEmpty())
                    destination = chooseDestination((QLearningMigrationPolicy) migrationPolicy, migratingModules,
                            newParent, applicationName);

                for (String moduleName : migratingModules.keySet()) {

                    int moduleAncestor = ancestorFor(migratingModules.get(moduleName), newParent.getId());
                    double upDelay = getUpDelay(migratingModules.get(moduleName), moduleAncestor, applications.get(applicationName).getModuleByName(moduleName));
                    double downDelay = getDownDelay(newParent.getId(), moduleAncestor, applications.get(applicationName).getModuleByName(moduleName));
                    if (destination.getId() != newParent.getId())
                        downDelay += clusterHopDelay(newParent, destination, applications.get(applicationName).getModuleByName(moduleName));
                    upDelays.put(moduleName, upDelay);
                    downDelays.put(moduleName, downDelay);
                    JSONObject jsonSend = new JSONObject();
                    jsonSend.put("module", applications.get(applicationName).getModuleByName(moduleName));
                    jsonSend.put("delay", upDelay);

                    JSONObject jsonReceive = new JSONObject();
                    jsonReceive.put("module", new AppModule(applications.get(applicationName).getModuleByName(moduleName)));
                    jsonReceive.put("delay", downDelay);
                    jsonReceive.put("application", applications.get(applicationName));

                    send(migratingModules.get(moduleName), upDelay, FogEvents.MODULE_SEND, jsonSend);
                    send(destination.getId(), downDelay, FogEvents.MODULE_RECEIVE, jsonReceive);
                    if (destination.getId() == newParent.getId())
                        System.out.println("Migrating " + moduleName + " from " + prevParent.getName() + " to " + newParent.getName());
                    else
                        System.out.println("Migrating " + moduleName + " from " + prevParent.getName() + " to " + destination.getName() + " (cluster peer of " + newParent.getName() + ")");
                }

                serviceDiscoveryUpdate(fogDevice, migratingModules, applicationName, destination.getId(), upDelays, downDelays);
                for (String moduleName : migratingModules.keySet()) {
                    //because modules are moved to the chosen destination (newParent, or one of its cluster peers)
                    perClientDevicePrs.get(fogDevice.getId()).get(applicationName).getPlacedMicroservices().put(moduleName, destination.getId());
                }
            }

            // = get
            //printFogDeviceChildren(newParent.getId());
            //printFogDeviceChildren(prevParent.getId());
        }

        updateRoutingTable(fogDevice);

    }

    private Map<String, Integer> filterByPolicy(FogDevice mobile, FogDevice newParent,
                                                String applicationName, Map<String, Integer> candidates) {
        long t0 = System.nanoTime();
        Map<String, Integer> chosen = new HashMap<>();
        for (Map.Entry<String, Integer> e : candidates.entrySet()) {
            AppModule module = applications.get(applicationName).getModuleByName(e.getKey());
            MigrationPolicy.Context c = new MigrationPolicy.Context();
            c.mobileName = mobile.getName();
            c.application = applicationName;
            c.module = e.getKey();
            c.fromDeviceId = e.getValue();
            c.toDeviceId = newParent.getId();
            c.moduleSize = module.getSize();
            c.predictedSojournTime = predictSojournTime(mobile);
            int moduleAncestor = ancestorFor(e.getValue(), newParent.getId());
            c.stayLatency = pathLatency(newParent.getId(), moduleAncestor) + pathLatency(e.getValue(), moduleAncestor);
            c.migrationDelay = getUpDelay(e.getValue(), moduleAncestor, module) + getDownDelay(newParent.getId(), moduleAncestor, module);
            double extraPower = newParent.getHost().getPowerModel().getPower(1.0) - newParent.getHost().getPowerModel().getPower(0.0);
            c.migrationEnergy = extraPower * c.migrationDelay;
            boolean decision;
            if (migrationPolicy instanceof QLearningMigrationPolicy)
                decision = ((QLearningMigrationPolicy) migrationPolicy)
                        .shouldMigrate(c, QLearningMigrationPolicy.resourceAvailability(newParent));
            else
                decision = migrationPolicy.shouldMigrate(c);
            if (decision) {
                chosen.put(e.getKey(), e.getValue());
                migrationsPerformed++;
            } else
                migrationsSkipped++;
        }
        decisionNanos += System.nanoTime() - t0;
        return chosen;
    }

    /**
     * Cluster-scoped destination search (learned policy only). Candidates are newParent itself plus
     * its cluster peers (FogDevice.getClusterMembers(), populated by iFogSim2's dynamic clustering).
     * Each candidate is scored by summing the agent's learned migrateScore over every module already
     * decided (by filterByPolicy) to migrate, using that candidate as the destination; the candidate
     * with the highest total is chosen. Falls back to newParent if no cluster peers are available or
     * a candidate's data cannot be read, so a lookup problem degrades to the original behaviour
     * instead of failing the run.
     */
    private FogDevice chooseDestination(QLearningMigrationPolicy qp, Map<String, Integer> migratingModules,
                                        FogDevice newParent, String applicationName) {
        List<FogDevice> candidates = new ArrayList<>();
        candidates.add(newParent);
        try {
            for (int peerId : newParent.getClusterMembers()) {
                FogDevice peer = getFogDeviceById(peerId);
                if (peer != null) candidates.add(peer);
            }
        } catch (Exception ignored) {
            // no cluster data available for this node; candidates stays as [newParent]
        }
        if (candidates.size() == 1) return newParent;

        FogDevice best = newParent;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (FogDevice dest : candidates) {
            double total = 0;
            try {
                for (Map.Entry<String, Integer> e : migratingModules.entrySet()) {
                    AppModule module = applications.get(applicationName).getModuleByName(e.getKey());
                    int moduleAncestor = ancestorFor(e.getValue(), newParent.getId());
                    MigrationPolicy.Context c = new MigrationPolicy.Context();
                    c.moduleSize = module.getSize();
                    c.stayLatency = pathLatency(newParent.getId(), moduleAncestor) + pathLatency(e.getValue(), moduleAncestor);
                    c.migrationDelay = getUpDelay(e.getValue(), moduleAncestor, module) + getDownDelay(newParent.getId(), moduleAncestor, module);
                    if (dest.getId() != newParent.getId())
                        c.migrationDelay += clusterHopDelay(newParent, dest, module);
                    total += qp.migrateScore(c, QLearningMigrationPolicy.resourceAvailability(dest));
                }
            } catch (Exception ignored) {
                continue; // skip a candidate we could not score rather than let it crash the run
            }
            if (total > bestScore) {
                bestScore = total;
                best = dest;
            }
        }
        return best;
    }

    /**
     * Extra one-hop transfer time to reach a cluster peer of newParent, beyond the delay already
     * costed to reach newParent itself. Approximated as module size over the smaller of the two
     * nodes' link bandwidths (no separate cluster-link bandwidth is modelled in iFogSim2); documented
     * as an approximation in the accompanying write-up.
     */
    private double clusterHopDelay(FogDevice newParent, FogDevice peer, AppModule module) {
        double bw = Math.min(newParent.getUplinkBandwidth(), peer.getDownlinkBandwidth());
        return bw > 0 ? module.getSize() / bw : 0.0;
    }

    /**
     * Time until this mobile's next scheduled handoff, read directly from iFogSim2's precomputed
     * mobility trace (LocationHandler.getTimeSheet), i.e. the simulator's own ground truth rather
     * than a kinematic estimate. Returns 0 if no future handoff is scheduled (end of trace).
     */
    private double predictSojournTime(FogDevice mobile) {
        try {
            for (double t : locator.getTimeSheet(mobile.getId()))
                if (t > CloudSim.clock())
                    return t - CloudSim.clock();
        } catch (Exception ignored) { /* no mobility data for this device */ }
        return 0;
    }

    /** First node shared by the paths-to-cloud of a module's current host and the user's new parent. */
    private int ancestorFor(int hostId, int newParentId) {
        return determineAncestor(getPathsToCloud(newParentId), getPathsToCloud(hostId));
    }

    /**
     * All of this user's placed microservices that could be moved closer: not on the mobile itself,
     * not already on the new parent, and not pinned by special placement (e.g. storage in the cloud).
     * Includes modules left behind at earlier handoffs.
     */
    private Map<String, Integer> getAllMovableModules(FogDevice mobile, FogDevice newParent, String applicationName) {
        Map<String, Integer> movable = new HashMap<>();
        PlacementRequest pr = perClientDevicePrs.get(mobile.getId()).get(applicationName);
        Application app = applications.get(applicationName);
        // A module already hosted on a cluster peer of newParent is "settled": close enough that it
        // is excluded from reconsideration, rather than offered as a candidate again at every handoff.
        // Without this, the multi-node destination search could keep relocating an already-nearby
        // module from peer to peer as the user moves within the same cluster (observed empirically as
        // migration counts and energy nearly doubling between otherwise-identical training runs, with
        // the "skipped" count collapsing toward zero): a settled module is only reconsidered once the
        // user leaves the cluster entirely, i.e. once its host is no longer among newParent's peers.
        java.util.Set<Integer> settledNeighbourhood = new java.util.HashSet<>();
        settledNeighbourhood.add(newParent.getId());
        try {
            for (int peerId : newParent.getClusterMembers()) settledNeighbourhood.add(peerId);
        } catch (Exception ignored) { /* no cluster data for this node; neighbourhood stays [newParent] */ }

        for (String microservice : pr.getPlacedMicroservices().keySet()) {
            int host = pr.getPlacedMicroservices().get(microservice);
            if (host == mobile.getId() || settledNeighbourhood.contains(host)) continue;
            if (app.getSpecialPlacementInfo().containsKey(microservice)) continue;
            movable.put(microservice, host);
        }
        return movable;
    }

    private double pathLatency(int deviceID, int commonAncestorID) {
        double latency = 0.0;
        while (deviceID != commonAncestorID) {
            latency += getFogDeviceById(deviceID).getUplinkLatency();
            deviceID = getFogDeviceById(deviceID).getParentId();
        }
        return latency;
    }

    private void setNewOrchestratorNode(FogDevice fogDevice, FogDevice newParent) {
        int parentId = newParent.getId();
        while(parentId!=-1){
            if(((MicroserviceFogDevice)newParent).getDeviceType().equals(MicroserviceFogDevice.FON)){
                int currentFon = ((MicroserviceFogDevice)fogDevice).getFonId();
                if(currentFon!=parentId) {
                    ((MicroserviceFogDevice)getFogDeviceById(currentFon)).removeMonitoredDevice(fogDevice);
                    ((MicroserviceFogDevice) fogDevice).setFonID(parentId);
                    ((MicroserviceFogDevice)getFogDeviceById(parentId)).addMonitoredDevice(fogDevice);
                    System.out.println("Orchestrator Node for device : " + fogDevice.getId() + " updated to " + parentId);
                }
                break;
            }
            else{
                parentId =newParent.getParentId();
                if(parentId!=-1)
                    newParent = getFogDeviceById(parentId);
            }
        }
    }

    private void updateRoutingTable(FogDevice fogDevice) {

        for (FogDevice f : fogDevices) {
            if (f.getId() != fogDevice.getId()) {
                // for mobile device update all to parent
                ((MicroserviceFogDevice) fogDevice).updateRoutingTable(f.getId(), fogDevice.getParentId());

                ////for other update route to mobile based on route to parent
                int nextId = ((MicroserviceFogDevice) f).getRoutingTable().get(fogDevice.getParentId());
                if (f.getId() != nextId)
                    ((MicroserviceFogDevice) f).updateRoutingTable(fogDevice.getId(), nextId);
                else
                    ((MicroserviceFogDevice) f).updateRoutingTable(fogDevice.getId(), fogDevice.getId());
            }
        }
    }

    private void serviceDiscoveryUpdate(FogDevice fogDevice, Map<String, Integer> migratingModules, String applicationName, int newParent, HashMap<String, Double> upDelays, HashMap<String, Double> downDelays) {
        PlacementRequest pr = perClientDevicePrs.get(fogDevice.getId()).get(applicationName);

        for (String m : migratingModules.keySet()) {
            List<String> clientMs = getClientMicroservices(m, applicationName);
            for (String clientM : clientMs) {
                JSONObject serviceDiscoveryRemove = new JSONObject();
                serviceDiscoveryRemove.put("service data", new Pair<>(m, migratingModules.get(m)));
                serviceDiscoveryRemove.put("action", "REMOVE");
                send(pr.getPlacedMicroservices().get(clientM), downDelays.get(m), FogEvents.UPDATE_SERVICE_DISCOVERY, serviceDiscoveryRemove);
            }
        }

        for (String m : pr.getPlacedMicroservices().keySet()) {
            if (pr.getPlacedMicroservices().get(m) == fogDevice.getId()) {
                List<String> services = getServiceMicroservice(m, applicationName);
                for (String service : services) {
                    if (migratingModules.containsKey(service)) {
                        JSONObject serviceDiscoveryAdd = new JSONObject();
                        serviceDiscoveryAdd.put("service data", new Pair<>(service, newParent));
                        serviceDiscoveryAdd.put("action", "ADD");
                        send(fogDevice.getId(), upDelays.get(service), FogEvents.UPDATE_SERVICE_DISCOVERY, serviceDiscoveryAdd);
                    }
                }
            }
        }

        for (String m : migratingModules.keySet()) {
            List<String> services = getServiceMicroservice(m, applicationName);
            for (String service : services) {
                if (migratingModules.containsKey(service)) {
                    JSONObject serviceDiscoveryAdd = new JSONObject();
                    serviceDiscoveryAdd.put("service data", new Pair<>(service, newParent));
                    serviceDiscoveryAdd.put("action", "ADD");
                    send(newParent, upDelays.get(service), FogEvents.UPDATE_SERVICE_DISCOVERY, serviceDiscoveryAdd);
                } else {
                    int d = pr.getPlacedMicroservices().get(service);
                    JSONObject serviceDiscoveryAdd = new JSONObject();
                    serviceDiscoveryAdd.put("service data", new Pair<>(service, d));
                    serviceDiscoveryAdd.put("action", "ADD");
                    sendNow(newParent, FogEvents.UPDATE_SERVICE_DISCOVERY, serviceDiscoveryAdd);
                }
            }
        }
    }

    private List<String> getClientMicroservices(String m, String applicationName) {
        List<String> services = new ArrayList<>();
        Application app = applications.get(applicationName);
        for (AppEdge appEdge : app.getEdges()) {
            if (appEdge.getDestination().equals(m) && appEdge.getDirection() == Tuple.UP) {
                if (app.getModuleNames().contains(appEdge.getSource()))
                    services.add(appEdge.getSource());
            }
        }
        return services;
    }

    private List<String> getServiceMicroservice(String m, String applicationName) {
        List<String> services = new ArrayList<>();
        Application app = applications.get(applicationName);
        for (AppEdge appEdge : app.getEdges()) {
            if (appEdge.getSource().equals(m) && appEdge.getDirection() == Tuple.UP) {
                if (app.getModuleNames().contains(appEdge.getDestination()))
                    services.add(appEdge.getDestination());
            }
        }
        return services;
    }

    private Map<String, Integer> getModulesToMigrate(FogDevice mobileDevice, int commonAncestor, String applicationName) {
        Map<String, Integer> migratingModules = new HashMap<>();

        PlacementRequest pr = perClientDevicePrs.get(mobileDevice.getId()).get(applicationName);
        for (String microservice : pr.getPlacedMicroservices().keySet()) {
            int deviceid = pr.getPlacedMicroservices().get(microservice);
            if (deviceid != mobileDevice.getId() && beforeCommonAncestor(deviceid, commonAncestor)) {
                migratingModules.put(microservice, deviceid);
            }
        }

        return migratingModules;
    }

    private boolean beforeCommonAncestor(Integer deviceid, int commonAncestor) {
        FogDevice f = getFogDeviceById(deviceid);
        if (f.getId() == commonAncestor)
            return false;
        while (f.getParentId() != -1) {
            f = getFogDeviceById(f.getParentId());
            if (f.getId() == commonAncestor)
                return true;
        }
        return false;
    }

    private double getDownDelay(int deviceID, int commonAncestorID, AppModule module) {
        // TODO Auto-generated method stub
        double networkDelay = 0.0;
        while (deviceID != commonAncestorID) {
            networkDelay = networkDelay + module.getSize() / getFogDeviceById(deviceID).getDownlinkBandwidth();
            deviceID = getFogDeviceById(deviceID).getParentId();
        }
        return networkDelay;
    }

    private double getUpDelay(int deviceID, int commonAncestorID, AppModule module) {
        // TODO Auto-generated method stub
        double networkDelay = 0.0;
        while (deviceID != commonAncestorID) {
            networkDelay = networkDelay + module.getSize() / getFogDeviceById(deviceID).getUplinkBandwidth();
            deviceID = getFogDeviceById(deviceID).getParentId();
        }
        return networkDelay;
    }

    private int determineAncestor(List<Integer> newParentPath, List<Integer> prevParentPath) {
        // TODO Auto-generated method stub
        List<Integer> common = newParentPath.stream().filter(prevParentPath::contains).collect(Collectors.toList());
        return common.get(0);
    }

    private List<Integer> getPathsToCloud(int deviceID) {
        // TODO Auto-generated method stub
        List<Integer> path = new ArrayList<Integer>();
        while (!locator.isCloud(deviceID)) {
            path.add(deviceID);
            deviceID = getFogDeviceById(deviceID).getParentId();
        }
        path.add(getCloud().getId());
        return path;
    }

    private void processMobilityData() {
        // TODO Auto-generated method stub
        List<Double> timeSheet = new ArrayList<Double>();
        for (FogDevice fogDevice : fogDevices) {
            if (locator.isAMobileDevice(fogDevice.getId())) {
                timeSheet = locator.getTimeSheet(fogDevice.getId());
                for (double timeEntry : timeSheet)
                    send(getId(), timeEntry, FogEvents.MOBILITY_MANAGEMENT, fogDevice);
            }
        }
    }


    public void clusteringSubmit(List Levels) {
        System.out.println(CloudSim.clock() + " Start sending Clustering Request to Fog Devices in level: " + Levels);
        for (int i = 0; i < Levels.size(); i++) {
            int clusterLevel = (int) Levels.get(i);
            for (FogDevice fogDevice : fogDevices) {
                System.out.println(CloudSim.clock() + " fog Device: " + fogDevice.getName() + " with id: " + fogDevice.getId() + " is at level: " + fogDevice.getLevel());
                if ((int) fogDevice.getLevel() == clusterLevel) {
                    JSONObject jsonMessage = new JSONObject();
                    jsonMessage.put("locationsInfo", getLocator());
                    sendNow(fogDevice.getId(), FogEvents.START_DYNAMIC_CLUSTERING, jsonMessage);
                }
            }
        }
    }

    public LocationHandler getLocator() {
        return locator;
    }

    public void setLocator(LocationHandler locator) {
        this.locator = locator;
    }

}