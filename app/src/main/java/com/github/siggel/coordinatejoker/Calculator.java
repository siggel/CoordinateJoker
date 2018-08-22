/*
 * Copyright (c) 2018 by siggel <siggel-apps@gmx.de>
 *
 *     This file is part of Coordinate Joker.
 *
 *     Coordinate Joker is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Coordinate Joker is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Coordinate Joker.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.siggel.coordinatejoker;

import android.content.Context;

import net.objecthunter.exp4j.ExpressionBuilder;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Class performing the calculations given string formulas and x value
 */
class Calculator {

    /**
     * constant for meter to feet conversion
     */
    final private static double meterPerFeet = 0.3048;

    /**
     * the formula mainModel
     */
    private final MainModel mainModel;


    /**
     * the app's main context (for accessing resource strings)
     */
    private final Context context;

    /**
     * constructor providing context and mainModel
     *
     * @param context   the main activities context
     * @param mainModel the formula mainModel
     */
    Calculator(Context context, MainModel mainModel) {
        this.context = context;
        this.mainModel = mainModel;
    }

    /**
     * method for calculating coordinates from the formulas given in the mainModel
     *
     * @return list of calculated waypoints
     */
    List<Point> solve() {

        try {
            // initialize return list
            List<Point> list = new ArrayList<>();

            List<Integer> xValues = mainModel.getXValues();
            if (xValues.size() == 0) {
                xValues = new ArrayList<>(1);
                xValues.add(0);
            }
            List<Integer> yValues = mainModel.getYValues();
            if (yValues.size() == 0) {
                yValues = new ArrayList<>(1);
                yValues.add(0);
            }

            for (int x : xValues) {
                for (int y : yValues) {

                    // get formulas from mainModel
                    String degreesNorth = mainModel.getDegreesNorth();
                    String degreesEast = mainModel.getDegreesEast();
                    String minutesNorth = mainModel.getMinutesNorth();
                    String minutesEast = mainModel.getMinutesEast();
                    String distance = mainModel.getDistance();
                    String azimuth = mainModel.getAzimuth();

                    // first evaluate north and east coordinate
                    double degrees = evaluate(degreesNorth, x, y);
                    double minutes = evaluate(minutesNorth, x, y);
                    if (invalidLatitudeDegrees(degrees) || invalidMinutes(minutes)) {
                        // skip invalid waypoints
                        continue;
                    }
                    double coordinateNorth =
                            (mainModel.getNorth() ? 1 : -1) * (degrees + (minutes / 60.0));

                    degrees = evaluate(degreesEast, x, y);
                    minutes = evaluate(minutesEast, x, y);
                    if (invalidLongitudeDegrees(degrees) || invalidMinutes(minutes)) {
                        // skip invalid waypoints
                        continue;
                    }
                    double coordinateEast =
                            (mainModel.getEast() ? 1 : -1) * (degrees + (minutes / 60.0));

                    // calculate projection delta
                    double deltaDistance = evaluate(distance, x, y);
                    if (mainModel.getFeet())
                        deltaDistance *= meterPerFeet;

                    double deltaAzimuth = evaluate(azimuth, x, y);
                    double deltaCoordinateNorth =
                            Math.cos(Math.toRadians(deltaAzimuth)) * deltaDistance;
                    double deltaCoordinateEast =
                            Math.sin(Math.toRadians(deltaAzimuth)) * deltaDistance
                                    / Math.cos(Math.toRadians(coordinateNorth));

                    // add projection delta to coordinate
                    coordinateNorth += deltaCoordinateNorth / 1850.0 / 60.0;
                    coordinateEast += deltaCoordinateEast / 1850.0 / 60.0;

                    StringBuilder name = new StringBuilder();
                    if (mainModel.getXValues().size() > 0) {
                        name.append("x=").append(x);
                    }
                    if (mainModel.getYValues().size() > 0) {
                        if (name.length() > 0) {
                            name.append(", ");
                        }
                        name.append("y=").append(y);
                    }

                    // add waypoint to list
                    Point point = new Point(
                            name.toString(),
                            coordinateNorth,
                            coordinateEast);
                    list.add(point);
                }
            }

            return list;

        } catch (Exception e) { // also catches RuntimeException
            throw new CalculatorException(context.getString(R.string.string_formula_error));
        }
    }

    private static DecimalFormat df = new DecimalFormat("0.##");

    /**
     * Evaluates a 'geocaching-mathematical' formula
     * @param formula
     * @param x
     * @param y
     * @return
     */
    public static double evaluate(String formula, Integer x, Integer y) {
        // Check if there are any parenthesis. If so then evaluate it first and
        // replace it with the value.
        formula = formula.replace(" ", "");
        int openIndex = findOpeningParenthesis(formula);
        int closeIndex = findCosingParenthesis(formula, openIndex);
        while (openIndex != -1 && closeIndex != -1 && openIndex < closeIndex) {
            // Evaluate the expression within the parenthesis and replace the
            // parenthesis-expression with the result. Repeat this until
            // there are no more parenthesis.
            String subFormula = formula.substring(openIndex + 1, closeIndex);
            double value = evaluate(subFormula, x, y);
            String replacement = df.format(value);
            formula = formula.substring(0, openIndex) + replacement + formula.substring(closeIndex + 1);
            openIndex = findOpeningParenthesis(formula);
            closeIndex = findCosingParenthesis(formula, openIndex);
        }

        // for non-negative x and y do simple replacement so we support formulas like 12.34x,
        // but don't replace "exp" as it serves as exponential function
        final String xString = "" + x;
        formula = formula.replaceAll("(?<!e)x(?!p)", xString);
        final String yString = "" + y;
        formula = formula.replace("y", yString);

        return new ExpressionBuilder(formula)
                .variables("x", "y")
                .build()
                .setVariable("x", x)
                .setVariable("y", y)
                .evaluate();
    }

    private static int findOpeningParenthesis(final String text) {
        return findOpeningParenthesis(text, 0);
    }

    private static int findOpeningParenthesis(final String text, int startIndex) {
        int index = text.indexOf('(', startIndex);
        if (index == -1) {
            return -1;
        }

        // Do not use the parenthesis if it is preceeded by a character a-w
        // Most likely this parenthesis is used for a function call.
        if (index > 0) {
            char charBefore = text.charAt(index - 1);
            if (charBefore >= 'a' && charBefore <= 'w') {
                return findOpeningParenthesis(text, index + 1);
            }
        }
        return index;
    }

    /**
     * Search the matching closing parenthesis given a text and the position of the
     * opening parenthesis.
     * @param text The text
     * @param indexOfOpenParenthesis The index of the opeing parenthesis
     * @return The index within text of the matching closing parenthesis or -1 if there is
     * no such closing parenthesis.
     */
    private static int findCosingParenthesis(final String text, final int indexOfOpenParenthesis) {
        if (indexOfOpenParenthesis == -1) {
            return -1;
        }
        int index = indexOfOpenParenthesis + 1;
        int numberOpens = 1;
        while (index < text.length() && numberOpens > 0) {
            char nextChar = text.charAt(index);
            if (nextChar == '(') {
                numberOpens++;
            } else if (nextChar == ')') {
                numberOpens--;
            }
            index++;
        }
        return numberOpens == 0 ? index - 1: -1;
    }

    /**
     * method checking if minutes are outside valid range
     *
     * @param minutes latitude or longitude minutes
     * @return true if minutes are outside range 0 <= minutes < 60
     */
    private boolean invalidMinutes(double minutes) {
        return minutes < 0 || minutes >= 60.0;
    }

    /**
     * method checking if latitude degrees are outside valid range
     *
     * @param degrees latitude degrees
     * @return true if degrees are outside range 0 <= minutes < 90
     */
    private boolean invalidLatitudeDegrees(double degrees) {
        return degrees < 0 || degrees >= 90.0;
    }

    /**
     * method checking if longitude degrees are outside valid range
     *
     * @param degrees longitude degrees
     * @return true if degrees are outside range 0 <= minutes < 90
     */
    private boolean invalidLongitudeDegrees(double degrees) {
        return degrees < 0 || degrees >= 180.0;
    }
}
