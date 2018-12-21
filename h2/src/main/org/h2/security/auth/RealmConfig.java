/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: Alessandro Ventura
 */
package org.h2.security.auth;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for authentication realm.
 */
public class RealmConfig implements HasConfigProperties {

    private String name;
    private String validatorClass;
    private List<PropertyConfig> properties;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }


    public String getValidatorClass() {
        return validatorClass;
    }

    public void setValidatorClass(String validatorClass) {
        this.validatorClass = validatorClass;
    }

    @Override
    public List<PropertyConfig> getProperties() {
        if (properties == null) {
            properties = new ArrayList<>();
        }
        return properties;
    }

}
