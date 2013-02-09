package org.jolokia.jmx;

/*
 * Copyright 2009-2013 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.Set;

import javax.management.*;
import javax.management.openmbean.OpenType;

import org.jolokia.converter.Converters;
import org.jolokia.converter.json.JsonConvertOptions;

/**
 * Dedicate MBeanServer for registering Jolokia-only MBeans
 *
 * @author roland
 * @since 11.01.13
 */
class JolokiaMBeanServer extends MBeanServerProxy {

    // MBeanServer to delegate to for JsonMBeans
    private MBeanServer     delegateServer;
    private Set<ObjectName> delegatedMBeans;

    private Converters converters;

    /**
     * Create a private MBean server
     */
    public JolokiaMBeanServer() {
        MBeanServer mBeanServer = MBeanServerFactory.newMBeanServer();
        delegatedMBeans = new HashSet<ObjectName>();
        delegateServer = ManagementFactory.getPlatformMBeanServer();
        converters = new Converters();
        init(mBeanServer);
    }

    @Override
    public ObjectInstance registerMBean(Object object, ObjectName name)
            throws InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException {
        // Register MBean first on this MBean Server
        ObjectInstance ret = super.registerMBean(object, name);

        // Check, whether it is annotated with @JsonMBean. Only the outermost class of an inheritance is
        // considered.
        JsonMBean anno = object.getClass().getAnnotation(JsonMBean.class);
        if (anno != null) {
            // The real name can be different than the given one in case the default
            // domain was omitted and/or the MBean implements MBeanRegistration
            ObjectName realName = ret.getObjectName();

            try {
                // Fetch real MBeanInfo and create a dynamic MBean with modified signature
                MBeanInfo info = super.getMBeanInfo(realName);
                JsonDynamicMBeanImpl mbean = new JsonDynamicMBeanImpl(this,realName,info);

                // Register MBean on delegate MBeanServer
                delegatedMBeans.add(realName);
                delegateServer.registerMBean(mbean,realName);
            } catch (InstanceNotFoundException e) {
                throw new MBeanRegistrationException(e,"Cannot obtain MBeanInfo from Jolokia-Server for " + realName);
            } catch (IntrospectionException e) {
                throw new MBeanRegistrationException(e,"Cannot obtain MBeanInfo from Jolokia-Server for " + realName);
            } catch (ReflectionException e) {
                throw new MBeanRegistrationException(e,"Cannot obtain MBeanInfo from Jolokia-Server for " + realName);
            }
        }
        return ret;
    }

    @Override
    public void unregisterMBean(ObjectName name) throws InstanceNotFoundException, MBeanRegistrationException {
        super.unregisterMBean(name);
        if (delegatedMBeans.contains(name)) {
            delegatedMBeans.remove(name);
            delegateServer.unregisterMBean(name);
        }
    }

    /**
     * Converter used by JsonMBean for converting from Object to JSON representation
     * @param object object to serialize
     * @return serialized object
     */
    String toJson(Object object) {
        try {
            Object ret = converters.getToJsonConverter().convertToJson(object,null, JsonConvertOptions.DEFAULT);
            return ret.toString();
        } catch (AttributeNotFoundException exp) {
            // Cannot happen, since we dont use a path
            return "";
        }
    }

    /**
     * Convert from a JSON or other string representation to real object. Used when preparing operation
     * argument. If the JSON structure cannot be converted, an {@link IllegalArgumentException} is thrown.
     *
     * @param type type to convert to
     * @param json string to deserialize
     * @return the deserialized object
     */
    Object fromJson(String type, String json) {
        return converters.getToObjectConverter().convertFromString(type,json);
    }

    /**
     * Convert from JSON for OpenType objects. Throws an {@link IllegalArgumentException} if
     *
     * @param type open type
     * @param json JSON representation to convert from
     * @return the converted object
     */
    Object fromJson(OpenType type, String json) {
        return converters.getToOpenTypeConverter().convertToObject(type,json);
    }


}