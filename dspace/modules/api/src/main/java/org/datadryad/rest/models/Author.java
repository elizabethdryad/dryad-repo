/*
 */
package org.datadryad.rest.models;

import javax.xml.bind.annotation.XmlRootElement;

/**
 *
 * @author Dan Leehr <dan.leehr@nescent.org>
 */
@XmlRootElement
public class Author {
    public String familyName;
    public String givenNames;
    public String identifier;
    public String identifierType;
    public Author() {}

    public Author(String familyName, String givenNames) {
        this.familyName = familyName;
        this.givenNames = givenNames;
    }

    public final String fullName() {
        String name = familyName;
        if (givenNames != null) {
            name = familyName + ", " + givenNames;
        }
        return name;
    }
}
