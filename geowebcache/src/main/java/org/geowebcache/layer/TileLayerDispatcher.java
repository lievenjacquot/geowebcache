/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Arne Kepp, The Open Planning Project, Copyright 2008
 */
package org.geowebcache.layer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.config.Configuration;
import org.geowebcache.config.meta.ServiceInformation;
import org.geowebcache.grid.GridSetBroker;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/**
 * Note that the constructor starts the thread to load configurations, making this class unsuitable for subclassing
 */
public class TileLayerDispatcher {
    private static Log log = LogFactory.getLog(org.geowebcache.layer.TileLayerDispatcher.class);

    private HashMap<String, TileLayer> layers = null;

    private List<Configuration> configs = null;

    private GridSetBroker gridSetBroker = null;
    
    private ServiceInformation serviceInformation = null;
    
    private ExecutorService configLoadService;
    private Future<?> configurationLoadTask;

    public TileLayerDispatcher(GridSetBroker gridSetBroker, List<Configuration> configs) {
        this(gridSetBroker, configs, 2);
    }
    
    public TileLayerDispatcher(GridSetBroker gridSetBroker, List<Configuration> configs, int loadDelay) {
        this.gridSetBroker = gridSetBroker;
        
        this.configs = configs;

        ThreadFactory tfac = new CustomizableThreadFactory("GWC Configuration loader thread");
        configLoadService = Executors.newSingleThreadExecutor(tfac);
        ConfigurationLoader loader = new ConfigurationLoader(this, loadDelay);
        configurationLoadTask = configLoadService.submit(loader);
    }

    public TileLayer getTileLayer(String layerIdent)
            throws GeoWebCacheException {

        checkConfigurationLoaded();
        
        TileLayer layer = layers.get(layerIdent);
        
        if (layer == null) {
            throw new GeoWebCacheException("Thread " + Thread.currentThread().getId() + " Unknown layer " + layerIdent
                    + ". Check the logfiles,"
                    + " it may not have loaded properly.");
        }

        return layer;
    }

    private void checkConfigurationLoaded() throws GeoWebCacheException {
        try {
            configurationLoadTask.get();
        } catch (InterruptedException e) {
            throw new GeoWebCacheException(e);
        } catch (ExecutionException e) {
            throw new GeoWebCacheException(e);
        }
    }

    /***
     * Reinitialization is tricky, because we can't really just lock all the
     * layers, because this would cause people to queue on something that we may
     * not want to exist post reinit.
     * 
     * So we'll just set the current layer set free, ready for garbage
     * collection, and generate a new one.
     * 
     * @throws GeoWebCacheException
     */
    public  void reInit() throws GeoWebCacheException {
        checkConfigurationLoaded();
        configurationLoadTask = configLoadService.submit(new ConfigurationLoader(this, 0));
    }
    
    /**
     * Returns a list of all the layers. The consumer may still have to
     * initialize each layer!
     * 
     * @return
     */
    public HashMap<String, TileLayer> getLayers() {
        try {
            checkConfigurationLoaded();
        } catch (GeoWebCacheException e) {
            throw new IllegalStateException(e);
        }

        return this.layers;
    }

    private HashMap<String, TileLayer> initialize(boolean reload) {
        log.debug("Thread initLayers(), initializing");

        HashMap<String, TileLayer> newLayers = new HashMap<String, TileLayer>();

        Iterator<Configuration> configIter = configs.iterator();
        
        while (configIter.hasNext()) {
            List<TileLayer> configLayers = null;

            Configuration config = configIter.next();

            String configIdent = null;
            try { 
                configIdent = config.getIdentifier();
            } catch (GeoWebCacheException gwce) {
                log.error(gwce.getMessage());
            }
            
            if (configIdent != null) {
                try {
                    // This is used by reload as well
                    configLayers = config.getTileLayers(reload);
                } catch (GeoWebCacheException gwce) {
                    log.error(gwce.getMessage());
                    log.error("Failed to add layers from " + configIdent);
                }

                log.trace("Adding layers from " + configIdent);
                if (configLayers != null && configLayers.size() > 0) {
                    Iterator<TileLayer> iter = configLayers.iterator();
                    
                    while (iter.hasNext()) {
                        TileLayer layer = iter.next();
                        
                        if(layer == null) {
                            log.error("layer was null");
                            continue;
                        }
                        log.info("Adding: " + layer.getName());
                        
                        layer.initialize(gridSetBroker);
                        
                        add(layer, newLayers);
                    }
                } else {
                    log.trace("Configuration " + configIdent
                            + " contained no layers.");
                }
                
                // Check whether there is any general service information
                if(this.serviceInformation == null) {
                    try {
                        this.serviceInformation = config.getServiceInformation();
                    } catch (GeoWebCacheException e) {
                        log.error("Error reading service information from "+ configIdent +": " + e.getMessage());
                    }
                }
            }
        }

        return newLayers;
    }
    
    public ServiceInformation getServiceInformation() {
        return this.serviceInformation;
    }
    
    public synchronized void update(TileLayer layer) {
        try {
            checkConfigurationLoaded();
        } catch (GeoWebCacheException e) {
            throw new IllegalStateException(e);
        }
        TileLayer oldLayer = layers.get(layer.getName());
        
        // Updates from GeoServer ultimately come as changes,
        // so we can't assume this layer actually existed
        if(oldLayer != null) {
            oldLayer.acquireLayerLock();
            layers.remove(layer.getName());
            oldLayer.releaseLayerLock();
        }
        layers.put(layer.getName(), layer);
    }
    
    public synchronized void remove(String layerName) {
        try {
            checkConfigurationLoaded();
        } catch (GeoWebCacheException e) {
            throw new IllegalStateException(e);
        }
        TileLayer layer = layers.get(layerName);
        if(layer != null) {
            layer.acquireLayerLock();
            layers.remove(layerName);
            layer.releaseLayerLock();
        }
    }
    
    public void add(TileLayer layer) {        
        try {
            checkConfigurationLoaded();
        } catch (GeoWebCacheException e) {
            throw new IllegalStateException(e);
        }
        add(layer, this.layers);
    }
    
    private void add(TileLayer layer, HashMap<String, TileLayer> layerMap) {        
        if(layerMap.containsKey(layer.getName())) {
            try {
                layerMap.get(layer.getName()).mergeWith(layer);
            } catch (GeoWebCacheException gwce) {
                log.error(gwce.getMessage());
            }
        } else {
            layerMap.put(layer.getName(), layer);
        }
    }
    
    private class ConfigurationLoader implements Runnable {
        
        TileLayerDispatcher parent;
        
        int loadDelay;
        
        private ConfigurationLoader(TileLayerDispatcher parent, int loadDelay) {
            this.parent = parent;
            this.loadDelay = loadDelay;
        }
        
        public void run() {
            if (loadDelay > 0) {
                log.info("ConfigurationLoader acquired lock, sleeping " + loadDelay + " seconds");
                try {
                    Thread.sleep(loadDelay * 1000);
                    log.trace("ConfigurationLoader woke up, initializing");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    throw new RuntimeException("Configuration loader thread interrupted", e);
                }
            }
            
            parent.layers = parent.initialize(false);
            
            log.trace("ConfigurationLoader completed");
        }
        
    }
}