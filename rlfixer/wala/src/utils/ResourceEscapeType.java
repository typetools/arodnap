package utils;

/* 
 * This enum gives the possible ways a resource can escape
 */
public enum ResourceEscapeType {
    FIELD, RETURN, INVOKE, ARRAY, PARAM, FIELD_SOURCE
}