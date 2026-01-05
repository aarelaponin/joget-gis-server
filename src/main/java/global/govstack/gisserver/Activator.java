package global.govstack.gisserver;

import global.govstack.gisserver.lib.GisApiProvider;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.util.ArrayList;
import java.util.List;

/**
 * OSGi Bundle Activator for Joget GIS Server Plugin.
 *
 * Registers the GIS API provider for geometry calculations, validation,
 * simplification, and overlap checking.
 */
public class Activator implements BundleActivator {

    protected List<ServiceRegistration> registrations = new ArrayList<>();

    @Override
    public void start(BundleContext context) throws Exception {
        // Register GIS API Provider
        registrations.add(context.registerService(
            GisApiProvider.class.getName(),
            new GisApiProvider(),
            null
        ));
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        for (ServiceRegistration registration : registrations) {
            registration.unregister();
        }
    }
}
