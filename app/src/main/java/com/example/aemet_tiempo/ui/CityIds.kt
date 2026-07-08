package com.example.aemet_tiempo.ui

import com.example.aemet_tiempo.data.Municipio

/**
 * Built-in list of large Spanish municipios. Used as a fallback for the
 * search picker when the full AEMET maestro/municipios list hasn't been
 * fetched yet (e.g. first launch with no network, AEMET down). The
 * list mirrors the web version's `FALLBACK_MUNIS` array.
 *
 * INE codes here are the 5-digit form (no "id" prefix), matching the
 * shape returned by `AemetClient.getMunicipios()`.
 */
val FALLBACK_MUNIS: List<Municipio> = listOf(
    Municipio(ine = "29067", nombre = "Málaga", destacada = true, hab = 566_913),
    Municipio(ine = "28079", nombre = "Madrid", destacada = true, hab = 3_300_000),
    Municipio(ine = "08019", nombre = "Barcelona", destacada = true, hab = 1_620_000),
    Municipio(ine = "41091", nombre = "Sevilla", destacada = true, hab = 688_000),
    Municipio(ine = "46250", nombre = "Valencia", destacada = true, hab = 789_000),
    Municipio(ine = "50297", nombre = "Zaragoza", destacada = true, hab = 681_000),
    Municipio(ine = "48020", nombre = "Bilbao", destacada = true, hab = 350_000),
    Municipio(ine = "33044", nombre = "Oviedo", destacada = true, hab = 220_000),
    Municipio(ine = "15030", nombre = "A Coruña", destacada = true, hab = 245_000),
    Municipio(ine = "35016", nombre = "Las Palmas de Gran Canaria", destacada = true, hab = 380_000),
    Municipio(ine = "30030", nombre = "Murcia", destacada = true, hab = 459_000),
    Municipio(ine = "07040", nombre = "Palma", destacada = true, hab = 419_000),
    Municipio(ine = "38038", nombre = "Santa Cruz de Tenerife", destacada = true, hab = 209_000),
    Municipio(ine = "11012", nombre = "Cádiz", destacada = true, hab = 116_000),
    Municipio(ine = "18087", nombre = "Granada", destacada = true, hab = 232_000),
    Municipio(ine = "37274", nombre = "Salamanca", destacada = true, hab = 144_000),
    Municipio(ine = "47186", nombre = "Valladolid", destacada = true, hab = 298_000),
    Municipio(ine = "39075", nombre = "Santander", destacada = true, hab = 171_000),
    Municipio(ine = "20069", nombre = "San Sebastián / Donostia", destacada = true, hab = 188_000),
    Municipio(ine = "01059", nombre = "Vitoria-Gasteiz", destacada = true, hab = 250_000),
    Municipio(ine = "31201", nombre = "Pamplona / Iruña", destacada = true, hab = 203_000),
    Municipio(ine = "26089", nombre = "Logroño", destacada = true, hab = 150_000),
    Municipio(ine = "03014", nombre = "Alicante / Alacant", destacada = true, hab = 338_000),
    Municipio(ine = "12040", nombre = "Castellón de la Plana", destacada = true, hab = 170_000),
    Municipio(ine = "06015", nombre = "Badajoz", destacada = true, hab = 150_000),
)

