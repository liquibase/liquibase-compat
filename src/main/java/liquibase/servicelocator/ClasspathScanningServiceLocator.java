package liquibase.servicelocator;

import liquibase.Scope;
import liquibase.exception.ServiceNotFoundException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.logging.Logger;
import liquibase.resource.InputStreamList;
import liquibase.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.jar.Manifest;

/**
 * Entry point to the Liquibase specific ServiceLocator framework.
 * <p>
 * Services (concrete instances of interfaces) are located by scanning nominated
 * packages on the classpath for implementations of the interface.
 */
public class ClasspathScanningServiceLocator implements ServiceLocator {

    private final ServiceLocator parentServiceLocator;
    private Map<Class, List<? extends Object>> objectsBySuperclass;
    private List<String> packagesToScan;
    private PackageScanClassResolver classResolver;

    public ClasspathScanningServiceLocator() {
        this.parentServiceLocator = Scope.getCurrentScope().getServiceLocator();
        this.objectsBySuperclass = new HashMap<>();
        this.classResolver = createPackageScanClassResolver();
        this.classResolver.setClassLoaders(new HashSet<>(Collections.singletonList(Scope.getCurrentScope().getClassLoader())));
        this.packagesToScan = createPackagesToScanList();
    }

    @Override
    public int getPriority() {
        return PRIORITY_SPECIALIZED;
    }

    protected PackageScanClassResolver createPackageScanClassResolver() {
        if (WebSpherePackageScanClassResolver.isWebSphereClassLoader(this.getClass().getClassLoader())) {
            Scope.getCurrentScope().getLog(getClass()).fine("Using WebSphere Specific Class Resolver");
            return new WebSpherePackageScanClassResolver("www.liquibase.org/xml/ns/dbchangelog/dbchangelog-2.0.xsd", parentServiceLocator);
        } else {
            return new DefaultPackageScanClassResolver(parentServiceLocator);
        }
    }

    protected List<String> createPackagesToScanList() {
        List<String> returnList = new ArrayList<>();
        String packagesToScanSystemProp = System.getProperty("liquibase.scan.packages");
        if ((packagesToScanSystemProp != null) &&
                ((packagesToScanSystemProp = StringUtils.trimToNull(packagesToScanSystemProp)) != null)) {
            returnList.addAll(Arrays.asList(packagesToScanSystemProp.split(",")));
        } else {
            InputStreamList manifests;
            try {
                manifests = Scope.getCurrentScope().getResourceAccessor().openStreams(null, "META-INF/MANIFEST.MF");
                if (manifests != null) {
                    for (InputStream is : manifests) {
                        Manifest manifest = new Manifest(is);
                        String attributes = StringUtils.trimToNull(manifest.getMainAttributes().getValue("Liquibase-Package"));
                        if (attributes != null) {
                            for (Object value : attributes.split(",")) {
                                returnList.add(value.toString());
                            }
                        }
                        is.close();
                    }
                }
            } catch (IOException e) {
                throw new UnexpectedLiquibaseException(e);
            }

            returnList.add("liquibase.change");
            returnList.add("liquibase.changelog");
            returnList.add("liquibase.command");
            returnList.add("liquibase.database");
            returnList.add("liquibase.parser");
            returnList.add("liquibase.precondition");
            returnList.add("liquibase.datatype");
            returnList.add("liquibase.serializer");
            returnList.add("liquibase.sqlgenerator");
            returnList.add("liquibase.executor");
            returnList.add("liquibase.snapshot");
            returnList.add("liquibase.logging");
            returnList.add("liquibase.diff");
            returnList.add("liquibase.structure");
            returnList.add("liquibase.structurecompare");
            returnList.add("liquibase.lockservice");
            returnList.add("liquibase.sdk.database");
            returnList.add("liquibase");
            returnList.add("liquibase.pro");
            returnList.add("com.datical.liquibase");
        }

        return returnList;
    }

    @Override
    public <T> List<T> findInstances(Class<T> interfaceType) throws ServiceNotFoundException {
        List<T> allInstances = new ArrayList<>();

        final Logger log = Scope.getCurrentScope().getLog(getClass());
        log.fine("ClasspathScanningServiceLocator.findInstances for " + interfaceType.getName());


        for (Object t : searchForImplementations(interfaceType)) {
            allInstances.add((T) t);
        }

        return Collections.unmodifiableList(allInstances);
    }

    private List searchForImplementations(Class requiredInterface) throws ServiceNotFoundException {
        final List<?> existingValue = objectsBySuperclass.get(requiredInterface.getName());
        if (existingValue != null) {
            return existingValue;
        }

        final Logger log = Scope.getCurrentScope().getLog(getClass());

        log.fine("ServiceLocator finding classes matching interface " + requiredInterface.getName());

        List objects = new ArrayList<>();

        objects.addAll(parentServiceLocator.findInstances(requiredInterface));


        classResolver.addClassLoader(Scope.getCurrentScope().getClassLoader(true));
        for (Class<?> clazz : classResolver.findImplementations(requiredInterface, packagesToScan.toArray(new String[packagesToScan.size()]))) {
            if ((clazz.getAnnotation(LiquibaseService.class) != null) && clazz.getAnnotation(LiquibaseService.class)
                    .skip()) {
                continue;
            }

            if (!Modifier.isAbstract(clazz.getModifiers()) && !Modifier.isInterface(clazz.getModifiers()) && !clazz.isAnonymousClass() && !clazz.isSynthetic() && Modifier.isPublic(clazz.getModifiers())) {
                try {
                    boolean objectAlreadyLoaded = false;
                    for (Object obj : objects) {
                        if (obj.getClass().getName().equals(clazz.getName())) {
                            log.fine("Parent ServiceLocator already loaded " + clazz.getName());

                            objectAlreadyLoaded = true;
                        }
                    }

                    if (objectAlreadyLoaded) {
                        continue;
                    }

                    clazz.getConstructor();
                    log.fine(clazz.getName() + " matches " + requiredInterface.getName());

                    objects.add(clazz.getConstructor().newInstance());
                } catch (ReflectiveOperationException e) {
                    log.info("Can not use " + clazz + " as a Liquibase service because it does not have a " +
                            "no-argument constructor");
                } catch (NoClassDefFoundError e) {
                    String message = "Can not use " + clazz + " as a Liquibase service because " + e.getMessage()
                            .replace("/", ".") + " is not in the classpath";
                    if (e.getMessage().startsWith("org/yaml/snakeyaml")) {
                        log.info(message);
                    } else {
                        log.warning(message);
                    }
                }
            }
        }

        objectsBySuperclass.put(requiredInterface, objects);

        return objects;
    }
}
