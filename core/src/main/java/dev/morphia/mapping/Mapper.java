package dev.morphia.mapping;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.lang.Nullable;

import dev.morphia.EntityInterceptor;
import dev.morphia.EntityListener;
import dev.morphia.Key;
import dev.morphia.annotations.Embedded;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.ExternalEntity;
import dev.morphia.annotations.PostLoad;
import dev.morphia.annotations.PostPersist;
import dev.morphia.annotations.PreLoad;
import dev.morphia.annotations.PrePersist;
import dev.morphia.annotations.internal.MorphiaInternal;
import dev.morphia.config.MorphiaConfig;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.mapping.codec.pojo.EntityModelBuilder;
import dev.morphia.mapping.codec.pojo.PropertyModel;
import dev.morphia.mapping.codec.references.MorphiaProxy;
import dev.morphia.mapping.validation.MappingValidator;
import dev.morphia.sofia.Sofia;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * @morphia.internal
 */
@MorphiaInternal
@SuppressWarnings({ "unchecked", "rawtypes", "removal" })
public class Mapper {
    private static final Logger LOG = LoggerFactory.getLogger(Mapper.class);

    /**
     * Special name that can never be used. Used as default for some fields to indicate default state.
     *
     * @morphia.internal
     */
    @MorphiaInternal
    public static final String IGNORED_FIELDNAME = ".";
    /**
     * @morphia.internal
     */
    @MorphiaInternal
    public static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(Entity.class, Embedded.class, ExternalEntity.class);
    @MorphiaInternal
    public static final List<Class<? extends Annotation>> LIFECYCLE_ANNOTATIONS = List.of(PrePersist.class,
            PreLoad.class,
            PostPersist.class,
            PostLoad.class);

    /**
     * Set of classes that registered by this mapper
     */
    private final Map<Class, EntityModel> mappedEntities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<EntityModel>> mappedEntitiesByCollection = new ConcurrentHashMap<>();
    private final List<EntityListener<?>> listeners = new ArrayList<>();
    private final MorphiaConfig config;
    private final DiscriminatorLookup discriminatorLookup;
    private final ClassLoader contextClassLoader;

    /**
     * Creates a Mapper with the given options.
     *
     * @param config the config to use
     * @morphia.internal
     */
    @MorphiaInternal
    public Mapper(MorphiaConfig config) {
        this.config = config;
        contextClassLoader = Thread.currentThread().getContextClassLoader();
        discriminatorLookup = new DiscriminatorLookup(contextClassLoader);
    }

    /**
     * Adds an {@link EntityInterceptor}
     *
     * @param ei the interceptor to add
     * @deprecated use {@link dev.morphia.annotations.EntityListeners} to define any lifecycle event listeners
     */
    @Deprecated(forRemoval = true, since = "2.4.0")
    public void addInterceptor(EntityListener<?> ei) {
        listeners.add(ei);
    }

    /**
     * Updates a collection to use a specific WriteConcern
     *
     * @param collection the collection to update
     * @param type       the entity type
     * @return the updated collection
     */
    public MongoCollection enforceWriteConcern(MongoCollection collection, Class type) {
        WriteConcern applied = getWriteConcern(type);
        return applied != null
                ? collection.withWriteConcern(applied)
                : collection;
    }

    /**
     * @param type the class
     * @return the id property model
     * @morphia.internal
     * @since 2.2
     */
    @MorphiaInternal
    public PropertyModel findIdProperty(Class<?> type) {
        EntityModel entityModel = getEntityModel(type);
        PropertyModel idField = entityModel.getIdProperty();

        if (idField == null) {
            throw new MappingException(Sofia.idRequired(type.getName()));
        }
        return idField;
    }

    /**
     * Gets the class as defined by any discriminator field
     *
     * @param document the document to check
     * @param <T>      the class type
     * @return the class reference. might be null
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> Class<T> getClass(Document document) {
        // see if there is a className value
        Class c = null;
        String discriminator = (String) document.get(getConfig().discriminatorKey());
        if (discriminator != null) {
            c = getClass(discriminator);
        }
        return c;
    }

    /**
     * @param discriminator the lookup value
     * @return the class mapped to this discrimiator value
     */
    public Class getClass(String discriminator) {
        return discriminatorLookup.lookup(discriminator);
    }

    /**
     * Looks up the class mapped to a named collection.
     *
     * @param collection the collection name
     * @param <T>        the class type
     * @return the Class mapped to this collection name
     * @morphia.internal
     */
    @MorphiaInternal
    public <T> Class<T> getClassFromCollection(String collection) {
        final List<EntityModel> classes = getClassesMappedToCollection(collection);
        if (classes.size() > 1) {
            LOG.warn(Sofia.moreThanOneMapper(collection,
                    classes.stream()
                            .map(c -> c.getType().getName())
                            .collect(Collectors.joining(", "))));
        }
        return (Class<T>) classes.get(0).getType();
    }

    /**
     * Finds all the types mapped to a named collection
     *
     * @param collection the collection to check
     * @return the mapped types
     * @morphia.internal
     */
    @MorphiaInternal
    public List<EntityModel> getClassesMappedToCollection(String collection) {
        final Set<EntityModel> entities = mappedEntitiesByCollection.get(collection);
        if (entities == null || entities.isEmpty()) {
            throw new MappingException(Sofia.collectionNotMapped(collection));
        }
        return new ArrayList<>(entities);
    }

    /**
     * @return the DiscriminatorLookup in use
     */
    public DiscriminatorLookup getDiscriminatorLookup() {
        return discriminatorLookup;
    }

    /**
     * Gets the {@link EntityModel} for the object (type). If it isn't mapped, create a new class and cache it (without validating).
     *
     * @param type the type to process
     * @return the EntityModel for the object given
     */
    public EntityModel getEntityModel(Class type) {
        final Class actual = MorphiaProxy.class.isAssignableFrom(type) ? type.getSuperclass() : type;
        EntityModel model = mappedEntities.get(actual);

        if (model == null) {
            if (!isMappable(actual)) {
                throw new NotMappableException(type);
            }
            model = register(createEntityModel(type));
        }

        return model;
    }

    /**
     * Gets the ID value for an entity
     *
     * @param entity the entity to process
     * @return the ID value
     */
    @Nullable
    public Object getId(@Nullable Object entity) {
        if (entity == null) {
            return null;
        }
        try {
            final EntityModel model = getEntityModel(entity.getClass());
            final PropertyModel idField = model.getIdProperty();
            if (idField != null) {
                return idField.getValue(entity);
            }
        } catch (NotMappableException ignored) {
        }

        return null;
    }

    /**
     * Gets list of {@link EntityInterceptor}s
     *
     * @return the Interceptors
     * @deprecated
     */
    @Deprecated(forRemoval = true, since = "2.4.0")
    public List<EntityListener<?>> getInterceptors() {
        return listeners;
    }

    /**
     * Gets the Key for an entity
     *
     * @param entity the entity to process
     * @param <T>    the type of the entity
     * @return the Key
     */
    @Nullable
    @Deprecated(since = "2.0", forRemoval = true)
    public <T> Key<T> getKey(T entity) {
        if (entity instanceof Key) {
            return (Key<T>) entity;
        }

        final Object id = getId(entity);
        final Class<T> aClass = (Class<T>) entity.getClass();
        return id == null ? null : new Key<>(aClass, getEntityModel(aClass).getCollectionName(), id);
    }

    /**
     * Gets the Key for an entity and a specific collection
     *
     * @param entity     the entity to process
     * @param collection the collection to use in the Key rather than the mapped collection as defined on the entity's class
     * @param <T>        the type of the entity
     * @return the Key
     */
    @Nullable
    @Deprecated(since = "2.0", forRemoval = true)
    public <T> Key<T> getKey(T entity, String collection) {
        if (entity instanceof Key) {
            return (Key<T>) entity;
        }

        final Object id = getId(entity);
        final Class<T> aClass = (Class<T>) entity.getClass();
        return id == null ? null : new Key<>(aClass, collection, id);
    }

    /**
     * @return collection of EntityModels
     */
    public List<EntityModel> getMappedEntities() {
        return new ArrayList<>(mappedEntities.values());
    }

    /**
     * @return the options used by this Mapper
     */
    public MorphiaConfig getConfig() {
        return config;
    }

    /**
     * Sets the options this Mapper should use
     *
     * @param options the options to use
     * @deprecated no longer used
     */
    @SuppressWarnings("unused")
    @Deprecated(since = "2.0", forRemoval = true)
    public void setOptions(MapperOptions options) {
    }

    /**
     * Gets the write concern for entity or returns the default write concern for this datastore
     *
     * @param clazz the class to use when looking up the WriteConcern
     * @return the write concern for the type
     * @morphia.internal
     */
    @Nullable
    public WriteConcern getWriteConcern(Class clazz) {
        WriteConcern wc = null;
        EntityModel entityModel = getEntityModel(clazz);
        if (entityModel != null) {
            final Entity entityAnn = entityModel.getEntityAnnotation();
            if (entityAnn != null && !entityAnn.concern().isEmpty()) {
                wc = WriteConcern.valueOf(entityAnn.concern());
            }
        }

        return wc;
    }

    /**
     * @return true if there are global interceptors defined
     */
    public boolean hasInterceptors() {
        return !listeners.isEmpty();
    }

    /**
     * Checks if a type is mappable or not
     *
     * @param type the class to check
     * @param <T>  the type
     * @return true if the type is mappable
     */
    public <T> boolean isMappable(Class<T> type) {
        final Class actual = MorphiaProxy.class.isAssignableFrom(type) ? type.getSuperclass() : type;
        return hasAnnotation(actual, MAPPING_ANNOTATIONS);
    }

    /**
     * Checks to see if a Class has been mapped.
     *
     * @param c the Class to check
     * @return true if the Class has been mapped
     */
    public boolean isMapped(Class c) {
        return mappedEntities.containsKey(c);
    }

    /**
     * Maps a set of classes
     *
     * @param entityClasses the classes to map
     * @return the EntityModel references
     * @deprecated This is handled via the config file and should not be called manually
     * @hidden
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public List<EntityModel> map(Class... entityClasses) {
        return map(List.of(entityClasses));
    }

    /**
     * Maps a set of classes
     *
     * @hidden
     * @param classes the classes to map
     * @return the list of mapped classes
     * @deprecated This is handled via the config file and should not be called manually
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public List<EntityModel> map(List<Class> classes) {
        Sofia.logConfiguredOperation("Mapper#map");
        for (Class type : classes) {
            if (!isMappable(type)) {
                throw new MappingException(Sofia.mappingAnnotationNeeded(type.getName()));
            }
        }
        return classes.stream()
                .map(this::getEntityModel)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Tries to map all classes in the package specified.
     *
     * @param packageName the name of the package to process
     * @deprecated This is handled via the config file and should not be called manually
     * @hidden
     * @since 2.4
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public synchronized void map(String packageName) {
        try {
            getClasses(contextClassLoader, packageName, getConfig().mapSubpackages())
                    .forEach(type -> {
                        try {
                            getEntityModel(type);
                        } catch (NotMappableException ignored) {
                        }
                    });
        } catch (ClassNotFoundException e) {
            throw new MappingException("Could not get map classes from package " + packageName, e);
        }
    }

    /**
     * Tries to map all classes in the package specified.
     *
     * @hidden
     * @param packageName the name of the package to process
     * @deprecated This is handled via the config file and should not be called manually
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public synchronized void mapPackage(String packageName) {
        Sofia.logConfiguredOperation("Mapper#mapPackage");
        try {
            getClasses(contextClassLoader, packageName, getConfig().mapSubpackages())
                    .forEach(type -> {
                        try {
                            getEntityModel(type);
                        } catch (NotMappableException ignored) {
                        }
                    });
        } catch (ClassNotFoundException e) {
            throw new MappingException("Could not get map classes from package " + packageName, e);
        }
    }

    /**
     * Maps all the classes found in the package to which the given class belongs.
     *
     * @hidden
     * @param clazz the class to use when trying to find others to map
     * @deprecated This is handled via the config file and should not be called manually
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public void mapPackageFromClass(Class clazz) {
        mapPackage(clazz.getPackage().getName());
    }

    /**
     * Updates the collection value on a Key with the mapped value on the Key's type Class
     *
     * @param key the Key to update
     * @return the collection name on the Key
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public String updateCollection(Key key) {
        String collection = key.getCollection();
        Class type = key.getType();
        if (collection == null && type == null) {
            throw new IllegalStateException("Key is invalid! " + key);
        } else if (collection == null) {
            collection = getEntityModel(type).getCollectionName();
            key.setCollection(collection);
        }

        return collection;
    }

    /**
     * Updates a query with any discriminators from subtypes if polymorphic queries are enabled
     *
     * @param model the query model
     * @param query the query document
     */
    public void updateQueryWithDiscriminators(EntityModel model, Document query) {
        Entity annotation = model.getEntityAnnotation();
        if (annotation != null && annotation.useDiscriminator()
                && !query.containsKey("_id")
                && !query.containsKey(model.getDiscriminatorKey())) {
            List<String> values = new ArrayList<>();
            values.add(model.getDiscriminator());
            if (config.enablePolymorphicQueries()) {
                for (EntityModel subtype : model.getSubtypes()) {
                    values.add(subtype.getDiscriminator());
                }
            }
            query.put(model.getDiscriminatorKey(),
                    new Document("$in", values));
        }
    }

    /**
     * @param entityModel the model to register
     * @return the model
     * @morphia.internal
     * @since 2.3
     */
    @MorphiaInternal
    public EntityModel register(EntityModel entityModel) {

        discriminatorLookup.addModel(entityModel);
        mappedEntities.put(entityModel.getType(), entityModel);
        mappedEntitiesByCollection.computeIfAbsent(entityModel.getCollectionName(), s -> new CopyOnWriteArraySet<>())
                .add(entityModel);
        EntityModel superClass = entityModel.getSuperClass();
        if (superClass != null) {
            superClass.addSubtype(entityModel);
        }

        if (!entityModel.isInterface()) {
            new MappingValidator()
                    .validate(this, entityModel);

        }
        return entityModel;
    }

    /**
     * @param clazz the model type
     * @param <T>   type model type
     * @return the new model
     */
    private <T> EntityModel createEntityModel(Class<T> clazz) {
        return new EntityModelBuilder(this, clazz)
                .build();
    }

    private List<Class> getClasses(ClassLoader loader, String packageName, boolean mapSubPackages)
            throws ClassNotFoundException {
        final Set<Class> classes = new HashSet<>();

        ClassGraph classGraph = new ClassGraph()
                .addClassLoader(loader)
                .enableAllInfo();
        if (mapSubPackages) {
            classGraph.acceptPackages(packageName);
            classGraph.acceptPackages(packageName + ".*");
        } else {
            classGraph.acceptPackagesNonRecursive(packageName);
        }

        try (ScanResult scanResult = classGraph.scan()) {
            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                try {
                    classes.add(Class.forName(classInfo.getName(), true, loader));
                } catch (NoClassDefFoundError ignored) {
                }
            }
        }
        return new ArrayList<>(classes);
    }

    private <T> boolean hasAnnotation(Class<T> clazz, List<Class<? extends Annotation>> annotations) {
        for (Class<? extends Annotation> annotation : annotations) {
            if (clazz.getAnnotation(annotation) != null) {
                return true;
            }
        }

        return clazz.getSuperclass() != null && hasAnnotation(clazz.getSuperclass(), annotations)
                || Arrays.stream(clazz.getInterfaces())
                        .map(i -> hasAnnotation(i, annotations))
                        .reduce(false, (l, r) -> l || r);
    }

}
