package com.ankiminer.android.data.resources

/*
 * The language-data entries of the spaCy languages' catalogs, mirroring
 * `app/src/main/python/android_bridge/resource_catalog/<code>.json` exactly (the JVM tests
 * compare them with the committed files). `FrozenResourceCatalog.all` lists them.
 */

internal val englishCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "en",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "en-en-core-web-sm",
                    displayName = "spaCy en_core_web_sm 3.8.0 pipeline",
                    importName = "en_core_web_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0-py3-none-any.whl",
                            sha256 = "1932429db727d4bff3deed6b34cfc05df17794f4a52eeb26cf8928f7c1a0fb85",
                            sizeBytes = 12_806_118,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "en_core_web_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "en_core_web_sm-3.8.0/config.cfg", "en_core_web_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "en_core_web_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "MIT",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "OntoNotes 5",
                                "Ralph Weischedel, Martha Palmer, Mitchell Marcus, Eduard Hovy, Sameer Pradhan, Lance Ramshaw, Nianwen Xue, Ann Taylor, Jeff Kaufman, Michelle Franchini, Mohammed El-Bachouti, Robert Belvin, Ann Houston",
                                "commercial (licensed by Explosion)",
                                "https://catalog.ldc.upenn.edu/LDC2013T19",
                            ),
                            ResourceAttribution(
                                "ClearNLP Constituent-to-Dependency Conversion",
                                "Emory University",
                                "Citation provided for reference, no code packaged with model",
                                "https://github.com/clir/clearnlp-guidelines/blob/master/md/components/dependency_conversion.md",
                            ),
                            ResourceAttribution(
                                "WordNet 3.0",
                                "Princeton University",
                                "WordNet 3.0 License",
                                "https://wordnet.princeton.edu/",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val catalanCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "ca",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "ca-ca-core-news-sm",
                    displayName = "spaCy ca_core_news_sm 3.8.0 pipeline",
                    importName = "ca_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/ca_core_news_sm-3.8.0/ca_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "e214211aa8da91c24ebdc453c2aa5f54fac09f44e01e65bcbdd3b0a5cb94d809",
                            sizeBytes = 19_566_606,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "ca_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "ca_core_news_sm-3.8.0/config.cfg", "ca_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "ca_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "GNU GPL 3.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Catalan AnCora v2.8",
                                "Martínez Alonso, Héctor; Pascual, Elena; Zeman, Daniel",
                                "GNU GPL 3.0",
                                "https://github.com/UniversalDependencies/UD_Catalan-AnCora",
                            ),
                            ResourceAttribution(
                                "UD Catalan AnCora v2.8 + NER v3.2.9",
                                "Carlos Rodríguez-Penagos and Carme Armentano-Oller",
                                "CC BY 4.0",
                                "https://github.com/TeMU-BSC/spacy/releases/tag/3.2.9",
                            ),
                            ResourceAttribution(
                                "Catalan Lemmatizer",
                                "Text Mining Unit, Barcelona Supercomputing Center",
                                "CC0",
                                "https://github.com/explosion/spacy-lookups-data",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val germanCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "de",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "de-de-core-news-sm",
                    displayName = "spaCy de_core_news_sm 3.8.0 pipeline",
                    importName = "de_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/de_core_news_sm-3.8.0/de_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "fec69fec52b1780f2d269d5af7582a5e28028738bd3190532459aeb473bfa3e7",
                            sizeBytes = 14_639_490,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "de_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "de_core_news_sm-3.8.0/config.cfg", "de_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "de_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "MIT",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "TIGER Corpus",
                                "Brants, Sabine, Stefanie Dipper, Peter Eisenberg, Silvia Hansen, Esther König, Wolfgang Lezius, Christian Rohrer, George Smith, and Hans Uszkoreit",
                                "commercial (licensed by Explosion)",
                                "https://www.ims.uni-stuttgart.de/forschung/ressourcen/korpora/tiger.html",
                            ),
                            ResourceAttribution(
                                "Tiger2Dep",
                                "Wolfgang Seeker",
                                "Citation provided for reference, no code packaged with model",
                                "https://www.ims.uni-stuttgart.de/forschung/ressourcen/werkzeuge/tiger2dep/",
                            ),
                            ResourceAttribution(
                                "WikiNER",
                                "Joel Nothman, Nicky Ringland, Will Radford, Tara Murphy, James R Curran",
                                "CC BY 4.0",
                                "https://figshare.com/articles/Learning_multilingual_named_entity_recognition_from_Wikipedia/5462500",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val portugueseCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "pt",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "pt-pt-core-news-sm",
                    displayName = "spaCy pt_core_news_sm 3.8.0 pipeline",
                    importName = "pt_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/pt_core_news_sm-3.8.0/pt_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "c304fa04db3af73cd08a250feacf560506e15a2ec2469bd1b09f06847f6b455c",
                            sizeBytes = 12_985_007,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "pt_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "pt_core_news_sm-3.8.0/config.cfg", "pt_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "pt_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-SA 4.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Portuguese Bosque v2.8",
                                "Rademaker, Alexandre; Freitas, Cláudia; de Souza, Elvis; Silveira, Aline; Cavalcanti, Tatiana; Evelyn, Wograine; Rocha, Luisa; Soares-Bastos, Isabela; Bick, Eckhard; Chalub, Fabricio; Paulino-Passos, Guilherme; Real, Livy; de Paiva, Valeria; Zeman, Daniel; Popel, Martin; Mareček, David; Silveira, Natalia; Martins, André",
                                "CC BY-SA 4.0",
                                "https://github.com/UniversalDependencies/UD_Portuguese-Bosque",
                            ),
                            ResourceAttribution(
                                "WikiNER",
                                "Joel Nothman, Nicky Ringland, Will Radford, Tara Murphy, James R Curran",
                                "CC BY 4.0",
                                "https://figshare.com/articles/Learning_multilingual_named_entity_recognition_from_Wikipedia/5462500",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val frenchCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "fr",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "fr-fr-core-news-sm",
                    displayName = "spaCy fr_core_news_sm 3.8.0 pipeline",
                    importName = "fr_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/fr_core_news_sm-3.8.0/fr_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "7d6ad14cd5078e53147bfbf70fb9d433c6a3865b695fda2657140bbc59a27e29",
                            sizeBytes = 16_271_721,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "fr_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "fr_core_news_sm-3.8.0/config.cfg", "fr_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "fr_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "LGPL-LR",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD French Sequoia v2.8",
                                "Candito, Marie; Seddah, Djamé; Perrier, Guy; Guillaume, Bruno",
                                "LGPL-LR",
                                "https://github.com/UniversalDependencies/UD_French-Sequoia",
                            ),
                            ResourceAttribution(
                                "WikiNER",
                                "Joel Nothman, Nicky Ringland, Will Radford, Tara Murphy, James R Curran",
                                "CC BY 4.0",
                                "https://figshare.com/articles/Learning_multilingual_named_entity_recognition_from_Wikipedia/5462500",
                            ),
                            ResourceAttribution(
                                "spaCy lookups data",
                                "Explosion",
                                "MIT",
                                "https://github.com/explosion/spacy-lookups-data",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val spanishCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "es",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "es-es-core-news-sm",
                    displayName = "spaCy es_core_news_sm 3.8.0 pipeline",
                    importName = "es_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/es_core_news_sm-3.8.0/es_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "e451a83d6df79b87e9eed0cb553f03e99e36a3bab18a7b79f0dcfd1fdf875e12",
                            sizeBytes = 12_884_212,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "es_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "es_core_news_sm-3.8.0/config.cfg", "es_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "es_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "GNU GPL 3.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Spanish AnCora v2.8",
                                "Martínez Alonso, Héctor; Zeman, Daniel",
                                "GNU GPL 3.0",
                                "https://github.com/UniversalDependencies/UD_Spanish-AnCora",
                            ),
                            ResourceAttribution(
                                "WikiNER",
                                "Joel Nothman, Nicky Ringland, Will Radford, Tara Murphy, James R Curran",
                                "CC BY 4.0",
                                "https://figshare.com/articles/Learning_multilingual_named_entity_recognition_from_Wikipedia/5462500",
                            ),
                            ResourceAttribution(
                                "spaCy lookups data",
                                "Explosion",
                                "MIT",
                                "https://github.com/explosion/spacy-lookups-data",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val italianCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "it",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "it-it-core-news-sm",
                    displayName = "spaCy it_core_news_sm 3.8.0 pipeline",
                    importName = "it_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/it_core_news_sm-3.8.0/it_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "3f617bf9a8ae0418953cf1fbf014e10272684c4229e882a7fd748b637d0100bf",
                            sizeBytes = 13_030_943,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "it_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "it_core_news_sm-3.8.0/config.cfg", "it_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "it_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-NC-SA 3.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Italian ISDT v2.8",
                                "Bosco, Cristina; Lenci, Alessandro; Montemagni, Simonetta; Simi, Maria",
                                "CC BY-NC-SA 3.0",
                                "https://github.com/UniversalDependencies/UD_Italian-ISDT",
                            ),
                            ResourceAttribution(
                                "WikiNER",
                                "Joel Nothman, Nicky Ringland, Will Radford, Tara Murphy, James R Curran",
                                "CC BY 4.0",
                                "https://figshare.com/articles/Learning_multilingual_named_entity_recognition_from_Wikipedia/5462500",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val dutchCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "nl",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "nl-nl-core-news-sm",
                    displayName = "spaCy nl_core_news_sm 3.8.0 pipeline",
                    importName = "nl_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/nl_core_news_sm-3.8.0/nl_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "a76978477821f213ca76a46c686df1b1d41462905d4868bc53eac086adca8b7e",
                            sizeBytes = 12_825_227,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "nl_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "nl_core_news_sm-3.8.0/config.cfg", "nl_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "nl_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-SA 4.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Dutch LassySmall v2.8",
                                "Bouma, Gosse; van Noord, Gertjan",
                                "CC BY-SA 4.0",
                                "https://github.com/UniversalDependencies/UD_Dutch-LassySmall",
                            ),
                            ResourceAttribution(
                                "Dutch NER Annotations for UD LassySmall",
                                "NLP Town",
                                "CC BY-SA 4.0",
                                "https://nlp.town",
                            ),
                            ResourceAttribution(
                                "UD Dutch Alpino v2.8",
                                "Zeman, Daniel; Žabokrtský, Zdeněk; Bouma, Gosse; van Noord, Gertjan",
                                "CC BY-SA 4.0",
                                "https://github.com/UniversalDependencies/UD_Dutch-Alpino",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val norwegianBokmalCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "nb",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "nb-nb-core-news-sm",
                    displayName = "spaCy nb_core_news_sm 3.8.0 pipeline",
                    importName = "nb_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/nb_core_news_sm-3.8.0/nb_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "086f2cfcc1568b2ee49a2ab297f7910f2645ae6892f2d5d15d8b077f5d0bd773",
                            sizeBytes = 12_488_349,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "nb_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "nb_core_news_sm-3.8.0/config.cfg", "nb_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "nb_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "MIT",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Norwegian Bokmaal v2.8",
                                "Øvrelid, Lilja; Jørgensen, Fredrik; Hohle, Petter",
                                "Public Domain (CC0)",
                                "https://github.com/UniversalDependencies/UD_Norwegian-Bokmaal",
                            ),
                            ResourceAttribution(
                                "NorNE: Norwegian Named Entities (commit: bd311de5)",
                                "Language Technology Group (University of Oslo)",
                                "Public Domain (CC0)",
                                "https://github.com/ltgoslo/norne",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val romanianCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "ro",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "ro-ro-core-news-sm",
                    displayName = "spaCy ro_core_news_sm 3.8.0 pipeline",
                    importName = "ro_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/ro_core_news_sm-3.8.0/ro_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "3cebfba2437131efe95345adcc7ea2131e4463915652df9099c577e618460cd9",
                            sizeBytes = 12_910_222,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "ro_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "ro_core_news_sm-3.8.0/config.cfg", "ro_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "ro_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-SA 4.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Romanian RRT v2.8",
                                "Barbu Mititelu, Verginica; Irimia, Elena; Perez, Cenel-Augusto; Ion, Radu; Simionescu, Radu; Popel, Martin",
                                "CC BY-SA 4.0",
                                "https://github.com/UniversalDependencies/UD_Romanian-RRT",
                            ),
                            ResourceAttribution(
                                "RONEC - the Romanian Named Entity Corpus (ca9ce460)",
                                "Dumitrescu, Stefan Daniel; Avram, Andrei-Marius; Morogan, Luciana; Toma; Stefan",
                                "MIT",
                                "https://github.com/dumitrescustefan/ronec",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val greekCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "el",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "el-el-core-news-sm",
                    displayName = "spaCy el_core_news_sm 3.8.0 pipeline",
                    importName = "el_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/el_core_news_sm-3.8.0/el_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "18df59b7f099a20d6f7cc1f964a57408a4c1663b73b5110932c1ea24f66e3027",
                            sizeBytes = 12_607_208,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "el_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "el_core_news_sm-3.8.0/config.cfg", "el_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "el_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-NC-SA 3.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Greek GDT v2.8",
                                "Prokopidis, Prokopis",
                                "CC BY-NC-SA 3.0",
                                "https://github.com/UniversalDependencies/UD_Greek-GDT",
                            ),
                            ResourceAttribution(
                                "Greek NER Corpus (Google Summer of Code 2018)",
                                "Giannis Daras",
                                "MIT",
                                "https://github.com/eellak/gsoc2018-spacy",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val finnishCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "fi",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "fi-fi-core-news-sm",
                    displayName = "spaCy fi_core_news_sm 3.8.0 pipeline",
                    importName = "fi_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/fi_core_news_sm-3.8.0/fi_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "5b9bd1496f500c1fac98f5e6a5d3913aa502f188c094ecea191dc6b8a81f418d",
                            sizeBytes = 14_346_621,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "fi_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "fi_core_news_sm-3.8.0/config.cfg", "fi_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "fi_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-SA 4.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Finnish TDT v2.8",
                                "Ginter, Filip; Kanerva, Jenna; Laippala, Veronika; Miekka, Niko; Missilä, Anna; Ojala, Stina; Pyysalo, Sampo",
                                "CC BY-SA 4.0",
                                "https://github.com/UniversalDependencies/UD_Finnish-TDT",
                            ),
                            ResourceAttribution(
                                "TurkuONE (ffe2040e)",
                                "Jouni Luoma, Li-Hsin Chang, Filip Ginter, Sampo Pyysalo",
                                "CC BY-SA 4.0",
                                "https://github.com/TurkuNLP/turku-one",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val hungarianCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "hu",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "hu-hu-core-news-md",
                    displayName = "spaCy hu_core_news_md 3.8.0 pipeline",
                    importName = "hu_core_news_md",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/huspacy/hu_core_news_md/resolve/v3.8.0/hu_core_news_md-any-py3-none-any.whl",
                            sha256 = "0fd89c6ccf0efe1d7591910065c3bec4eadb1e25313d6ceea551150832b0f861",
                            sizeBytes = 127_018_056,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "hu_core_news_md/",
                            exclude = listOf("__init__.py", "edit_tree_lemmatizer.py", "lemma_postprocessing.py", "lookup_lemmatizer.py"),
                            sentinels = listOf("meta.json", "hu_core_news_md-3.8.0/config.cfg", "hu_core_news_md-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "hu_core_news_md 3.8.0 (spaCy pipeline)",
                                "SzegedAI, MILAB",
                                "cc-by-sa-4.0",
                                "https://github.com/huspacy/huspacy",
                            ),
                            ResourceAttribution(
                                "UD Hungarian Szeged",
                                "Richárd Farkas, Katalin Simkó, Zsolt Szántó, Viktor Varga, Veronika Vincze (MTA-SZTE Research Group on Artificial Intelligence)",
                                "CC-BY-NC-SA-3.0",
                                "https://universaldependencies.org/treebanks/hu_szeged/index.html",
                            ),
                            ResourceAttribution(
                                "NYTK-NerKor Corpus",
                                "Eszter Simon, Noémi Vadász (Department of Language Technology and Applied Linguistics)",
                                "CC BY-SA 4.0",
                                "https://github.com/nytud/NYTK-NerKor",
                            ),
                            ResourceAttribution(
                                "Szeged NER Corpus",
                                "György Szarvas, Richárd Farkas, László Felföldi, András Kocsor, János Csirik (MTA-SZTE Research Group on Artificial Intelligence)",
                                "CC-BY-NC-SA-3.0",
                                "https://rgai.inf.u-szeged.hu/node/130",
                            ),
                            ResourceAttribution(
                                "Hungarian lg Floret vectors",
                                "Szeged AI",
                                "CC-BY-SA-4.0",
                                "https://huggingface.co/huspacy/hu_vectors_web_lg",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val croatianCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "hr",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "hr-hr-core-news-sm",
                    displayName = "spaCy hr_core_news_sm 3.8.0 pipeline",
                    importName = "hr_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/hr_core_news_sm-3.8.0/hr_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "393ad455bba13536e6fb257bce2b5ef0253f41c1fb5249d8c16dde6cf116d196",
                            sizeBytes = 13_187_227,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "hr_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "hr_core_news_sm-3.8.0/config.cfg", "hr_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "hr_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-SA 4.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "Training corpus hr500k 1.0",
                                "Ljubešić, Nikola ; Agić, Željko ; Klubička, Filip ; Batanović, Vuk and Erjavec, Tomaž",
                                "CC BY-SA 4.0",
                                "https://hdl.handle.net/11356/1183",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val swedishCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "sv",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "sv-sv-core-news-sm",
                    displayName = "spaCy sv_core_news_sm 3.8.0 pipeline",
                    importName = "sv_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/sv_core_news_sm-3.8.0/sv_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "be4929fb30523dca0b6672f999cdbf4d64f165419f1eed0014ca3a36599b8b4d",
                            sizeBytes = 12_741_934,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "sv_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "sv_core_news_sm-3.8.0/config.cfg", "sv_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "sv_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-SA 4.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Swedish Talbanken v2.8",
                                "Nivre, Joakim; Smith, Aaron",
                                "CC BY-SA 4.0",
                                "https://github.com/UniversalDependencies/UD_Swedish-Talbanken",
                            ),
                            ResourceAttribution(
                                "Stockholm-Umeå Corpus (SUC) v3.0",
                                "Språkbanken",
                                "CC BY 4.0",
                                "https://huggingface.co/datasets/KBLab/sucx3_ner",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val polishCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "pl",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "pl-pl-core-news-sm",
                    displayName = "spaCy pl_core_news_sm 3.8.0 pipeline",
                    importName = "pl_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/pl_core_news_sm-3.8.0/pl_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "9b536db854b0cdb9132a74f68b392b8112ab6030f971bb03282462362a940dbb",
                            sizeBytes = 20_213_213,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "pl_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "pl_core_news_sm-3.8.0/config.cfg", "pl_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "pl_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "GNU GPL 3.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Polish PDB v2.8",
                                "Wróblewska, Alina; Zeman, Daniel; Mašek, Jan; Rosa, Rudolf",
                                "GNU GPL 3.0",
                                "https://github.com/UniversalDependencies/UD_Polish-PDB/",
                            ),
                            ResourceAttribution(
                                "National Corpus of Polish",
                                "Mirosław Bańko, Rafał L. Górski, Barbara Lewandowska-Tomaszczyk, Marek Łaziński, Piotr Pęzik, Adam Przepiórkowski",
                                "GNU GPL 3.0",
                                "https://nkjp.pl/",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val lithuanianCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "lt",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "lt-lt-core-news-sm",
                    displayName = "spaCy lt_core_news_sm 3.8.0 pipeline",
                    importName = "lt_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/lt_core_news_sm-3.8.0/lt_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "c1f709112fd01771c10fbd2fe7e01d9d65712c8bccd9171065a29f91c9dc151e",
                            sizeBytes = 13_246_100,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "lt_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "lt_core_news_sm-3.8.0/config.cfg", "lt_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "lt_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-SA 4.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Lithuanian ALKSNIS v2.8",
                                "Utka, Andrius; Rimkutė, Erika; Bielinskienė, Agnė; Kovalevskaitė, Jolanta; Boizou, Loïc; Aleksandravičiūtė, Gabrielė; Brokaitė, Kristina; Zeman, Daniel; Perkova, Natalia; Griciūtė, Bernadeta",
                                "CC BY-SA 4.0",
                                "https://github.com/UniversalDependencies/UD_Lithuanian-ALKSNIS",
                            ),
                            ResourceAttribution(
                                "TokenMill NER Corpus",
                                "TokenMill",
                                "commercial (licensed by Explosion)",
                                "https://www.tokenmill.lt",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val danishCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "da",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "da-da-core-news-sm",
                    displayName = "spaCy da_core_news_sm 3.8.0 pipeline",
                    importName = "da_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/da_core_news_sm-3.8.0/da_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "8caeb4dc26f56de8abcf70399b202a0b2223d38664b1a59f1cb8db0000808bc9",
                            sizeBytes = 12_378_278,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "da_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "da_core_news_sm-3.8.0/config.cfg", "da_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "da_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-SA 4.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Danish DDT v2.8",
                                "Johannsen, Anders; Martínez Alonso, Héctor; Plank, Barbara",
                                "CC BY-SA 4.0",
                                "https://github.com/UniversalDependencies/UD_Danish-DDT",
                            ),
                            ResourceAttribution(
                                "DaNE",
                                "Rasmus Hvingelby, Amalie B. Pauli, Maria Barrett, Christina Rosted, Lasse M. Lidegaard, Anders Søgaard",
                                "CC BY-SA 4.0",
                                "https://github.com/alexandrainst/danlp/blob/master/docs/datasets.md",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val slovenianCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "sl",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "sl-sl-core-news-sm",
                    displayName = "spaCy sl_core_news_sm 3.8.0 pipeline",
                    importName = "sl_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/sl_core_news_sm-3.8.0/sl_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "d6d11f38c917d59b0fe52a15da09963bdc8011d2be288f75371e734065b6911c",
                            sizeBytes = 13_643_819,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "sl_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "sl_core_news_sm-3.8.0/config.cfg", "sl_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "sl_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "CC BY-SA 4.0",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "UD Slovenian SSJ v2.11",
                                "Dobrovoljc, Kaja; Erjavec, Tomaž; Krek, Simon",
                                "CC BY-SA 4.0",
                                "https://github.com/UniversalDependencies/UD_Slovenian-SSJ",
                            ),
                            ResourceAttribution(
                                "Training corpus SUK 1.0",
                                "Arhar Holdt, Špela; Krek, Simon; Dobrovoljc, Kaja; Erjavec, Tomaž; Gantar, Polona; Čibej, Jaka; Pori, Eva; Terčon, Luka; Munda, Tina; Žitnik, Slavko; Robida, Nejc; Blagus, Neli; Može, Sara; Ledinek, Nina; Holz, Nanika; Zupan, Katja; Kuzman, Taja; Kavčič, Teja; Škrjanec, Iza; Marko, Dafne; Jezeršek, Lucija; Zajc, Anja",
                                "CC BY-SA 4.0",
                                "https://www.clarin.si/repository/xmlui/handle/11356/1747",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val russianCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "ru",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "ru-ru-core-news-sm",
                    displayName = "spaCy ru_core_news_sm 3.8.0 pipeline",
                    importName = "ru_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/ru_core_news_sm-3.8.0/ru_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "69978d47b43e2c4f329bebdb155e8e9d3861bba1a58ba25551419dae7d7e07fc",
                            sizeBytes = 15_259_622,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "ru_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "ru_core_news_sm-3.8.0/config.cfg", "ru_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "ru_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "MIT",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "Nerus",
                                "Alexander Kukushkin",
                                "MIT",
                                "https://github.com/natasha/nerus",
                            ),
                        ),
                ),
                LanguageDataCatalogResource(
                    resourceId = "ru-pymorphy3-dicts-ru",
                    displayName = "pymorphy3 Russian dictionaries 2.4.417150.4580142",
                    importName = "pymorphy3_dicts_ru",
                    archive =
                        ResourceArchive(
                            url = "https://files.pythonhosted.org/packages/b0/67/469e9e52d046863f5959928794d3067d455a77f580bf4a662630a43eb426/pymorphy3_dicts_ru-2.4.417150.4580142-py2.py3-none-any.whl",
                            sha256 = "718bac64c73c10c16073a199402657283d9b64c04188b694f6d3e9b0d85440f4",
                            sizeBytes = 8_442_043,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "pymorphy3_dicts_ru/",
                            exclude = listOf("__init__.py", "version.py"),
                            sentinels = listOf("data/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "pymorphy3-dicts-ru 2.4.417150.4580142 (OpenCorpora-format dictionaries)",
                                "Danylo Halaiko and the pymorphy3-dicts contributors",
                                "MIT",
                                "https://github.com/no-plagiarism/pymorphy3-dicts",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )

internal val ukrainianCatalog =
    ResourceCatalog(
        schemaVersion = 3,
        language = "uk",
        resources =
            listOf(
                LanguageDataCatalogResource(
                    resourceId = "uk-uk-core-news-sm",
                    displayName = "spaCy uk_core_news_sm 3.8.0 pipeline",
                    importName = "uk_core_news_sm",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/explosion/spacy-models/releases/download/uk_core_news_sm-3.8.0/uk_core_news_sm-3.8.0-py3-none-any.whl",
                            sha256 = "d20adb50b42c0dcfdedf4994dabcb96789a64983a9ab560d0c6c38a59e8efb58",
                            sizeBytes = 14_908_659,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "uk_core_news_sm/",
                            exclude = listOf("__init__.py"),
                            sentinels = listOf("meta.json", "uk_core_news_sm-3.8.0/config.cfg", "uk_core_news_sm-3.8.0/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "uk_core_news_sm 3.8.0 (spaCy pipeline)",
                                "Explosion",
                                "MIT",
                                "https://explosion.ai",
                            ),
                            ResourceAttribution(
                                "Ukr-Synth (e5d9eaf3)",
                                "Volodymyr Kurnosov",
                                "MIT",
                                "https://huggingface.co/datasets/ukr-models/Ukr-Synth",
                            ),
                        ),
                ),
                LanguageDataCatalogResource(
                    resourceId = "uk-pymorphy3-dicts-uk",
                    displayName = "pymorphy3 Ukrainian dictionaries 2.4.1.1.1663094765",
                    importName = "pymorphy3_dicts_uk",
                    archive =
                        ResourceArchive(
                            url = "https://files.pythonhosted.org/packages/60/1a/310e767e0dd9ad414c11f9b969735b8e7af90c38b7372ebc3a086f3c1249/pymorphy3_dicts_uk-2.4.1.1.1663094765-py2.py3-none-any.whl",
                            sha256 = "6af1e389b69e7e90ef7d50b20e3955a0c78383317a15d9ade5979f1536354733",
                            sizeBytes = 8_201_207,
                            format = "wheel",
                        ),
                    install =
                        LanguageDataInstallIdentity(
                            memberPrefix = "pymorphy3_dicts_uk/",
                            exclude = listOf("__init__.py", "version.py"),
                            sentinels = listOf("data/meta.json"),
                            innerSha256 = emptyList(),
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution(
                                "pymorphy3-dicts-uk 2.4.1.1.1663094765 (OpenCorpora-format dictionaries)",
                                "Danylo Halaiko and the pymorphy3-dicts contributors",
                                "GPL-3.0",
                                "https://github.com/no-plagiarism/pymorphy3-dicts",
                            ),
                        ),
                ),
            ),
        recommended = emptyList(),
    )
