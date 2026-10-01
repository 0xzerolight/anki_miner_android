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
                YomitanCatalogResource(
                    resourceId = "wty-en-en-2026.09.20",
                    displayName = "Wiktionary (English) 2026-09-20",
                    slotId = "wty-en-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/en/en/wty-en-en.zip",
                            sha256 = "53ad4693a2b3c00d1a6722c6c2f3ff388abccd2e1e22ce19ebc5144124afb31d",
                            sizeBytes = 107_649_797,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-en-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 70,
                            uncompressedBytes = 1_113_640_806,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 134_217_728,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-en-2018",
                    displayName = "OpenSubtitles 2018 frequency (English)",
                    sourceId = "opensubtitles-en",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/en/en_50k.txt",
                            sha256 = "5351ff405b1126ef555791dd4d9798a48e3e9a501a9fc481a9da957752cfb458",
                            sizeBytes = 622_749,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-en-en-2026.09.20", "opensubtitles-en-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-ca-en-2026.09.20",
                    displayName = "Wiktionary (Catalan-English) 2026-09-20",
                    slotId = "wty-ca-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/ca/en/wty-ca-en.zip",
                            sha256 = "adcdae56d10932ceab99c2d4d411a011368b2aab8049af141967e52e56a207be",
                            sizeBytes = 4_639_237,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-ca-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 15,
                            uncompressedBytes = 64_964_990,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-ca-2018",
                    displayName = "OpenSubtitles 2018 frequency (Catalan)",
                    sourceId = "opensubtitles-ca",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/ca/ca_50k.txt",
                            sha256 = "3140bdce43ae1cd40b2dfa2ec1291f4917f477ff7d0573e4b77c6f1bc1d3ffad",
                            sizeBytes = 564_792,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-ca-en-2026.09.20", "opensubtitles-ca-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-de-en-2026.09.20",
                    displayName = "Wiktionary (German-English) 2026-09-20",
                    slotId = "wty-de-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/de/en/wty-de-en.zip",
                            sha256 = "0fec270f324df4b0ecd96666e196eb42462d5e75e243c6a507c0ac3f9dc36893",
                            sizeBytes = 17_104_879,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-de-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 28,
                            uncompressedBytes = 222_319_274,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 67_108_864,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-de-2018",
                    displayName = "OpenSubtitles 2018 frequency (German)",
                    sourceId = "opensubtitles-de",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/de/de_50k.txt",
                            sha256 = "d9e50546fd7e8b6fe6542a2b33c51d1331092b2a3916ec09f80d97856068705b",
                            sizeBytes = 662_497,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-de-en-2026.09.20", "opensubtitles-de-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-pt-en-2026.09.20",
                    displayName = "Wiktionary (Portuguese-English) 2026-09-20",
                    slotId = "wty-pt-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/pt/en/wty-pt-en.zip",
                            sha256 = "0e1fa35833715be3688f2c01a3742c39fa1418a7862ee85fdca77331bbb871c4",
                            sizeBytes = 11_334_569,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-pt-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 27,
                            uncompressedBytes = 142_561_502,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-pt-br-2018",
                    displayName = "OpenSubtitles 2018 frequency (Brazilian Portuguese)",
                    sourceId = "opensubtitles-pt-br",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/pt_br/pt_br_50k.txt",
                            sha256 = "a61d6f2ede97c5daad5fb3907b72a228f0aff12668be6d24da590d20804fa611",
                            sizeBytes = 650_066,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-pt-2018",
                    displayName = "OpenSubtitles 2018 frequency (European Portuguese)",
                    sourceId = "opensubtitles-pt",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/pt/pt_50k.txt",
                            sha256 = "f97704382f97273ff59488b1885e7ac90d20f81fd92e5fbad3e3ce43633f495c",
                            sizeBytes = 652_361,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-pt-en-2026.09.20", "opensubtitles-pt-br-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-fr-en-2026.09.20",
                    displayName = "Wiktionary (French-English) 2026-09-20",
                    slotId = "wty-fr-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/fr/en/wty-fr-en.zip",
                            sha256 = "bb9f989d892c7b82c4aaf2f616259a2d5d0e36d6edf19ddc0d533f6a2a0151a3",
                            sizeBytes = 11_001_259,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-fr-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 23,
                            uncompressedBytes = 145_647_643,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-fr-2018",
                    displayName = "OpenSubtitles 2018 frequency (French)",
                    sourceId = "opensubtitles-fr",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/fr/fr_50k.txt",
                            sha256 = "f81f7c570b6433764da99aa30f4dfb08d81c5926301377b709af23a13b1f9596",
                            sizeBytes = 653_452,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-fr-en-2026.09.20", "opensubtitles-fr-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-es-en-2026.09.20",
                    displayName = "Wiktionary (Spanish-English) 2026-09-20",
                    slotId = "wty-es-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/es/en/wty-es-en.zip",
                            sha256 = "f9bceb1d765b3e45e32dbda909c90cb35315f594aac43d3090c382f8ea22b29f",
                            sizeBytes = 21_731_793,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-es-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 64,
                            uncompressedBytes = 349_284_185,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-es-2018",
                    displayName = "OpenSubtitles 2018 frequency (Spanish)",
                    sourceId = "opensubtitles-es",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/es/es_50k.txt",
                            sha256 = "dcff3ad4316192f4dc4ff7d26e637c6ff314ef1ca0f3f720c5649018a71056c0",
                            sizeBytes = 658_626,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-es-en-2026.09.20", "opensubtitles-es-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-it-en-2026.09.20",
                    displayName = "Wiktionary (Italian-English) 2026-09-20",
                    slotId = "wty-it-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/it/en/wty-it-en.zip",
                            sha256 = "5e2490eaeb1362352c20ad70530bec018abf34471e657712912bc17f41ff3132",
                            sizeBytes = 17_229_031,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-it-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 53,
                            uncompressedBytes = 264_249_229,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-it-2018",
                    displayName = "OpenSubtitles 2018 frequency (Italian)",
                    sourceId = "opensubtitles-it",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/it/it_50k.txt",
                            sha256 = "bb96cdcb56d28342c1e909db6b2525448b7767136b7e86a2ccc649a1be66fc19",
                            sizeBytes = 653_205,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-it-en-2026.09.20", "opensubtitles-it-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-nl-en-2026.09.20",
                    displayName = "Wiktionary (Dutch-English) 2026-09-20",
                    slotId = "wty-nl-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/nl/en/wty-nl-en.zip",
                            sha256 = "d7f7448db748718faee8d4fb0d7a38e5251ef07509be67b058a743e3f523c78e",
                            sizeBytes = 8_590_098,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-nl-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 14,
                            uncompressedBytes = 102_454_347,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-nl-2018",
                    displayName = "OpenSubtitles 2018 frequency (Dutch)",
                    sourceId = "opensubtitles-nl",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/nl/nl_50k.txt",
                            sha256 = "099bd0c27b514d54284360ad9ce2b4ef7b6903b22e848b67ab10f6aa2adb4baa",
                            sizeBytes = 650_762,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-nl-en-2026.09.20", "opensubtitles-nl-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-nb-en-2026.09.20",
                    displayName = "Wiktionary (Norwegian Bokmål-English) 2026-09-20",
                    slotId = "wty-nb-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/nb/en/wty-nb-en.zip",
                            sha256 = "9d63021cc2d8781a8909e2f85c4fa0bc8a34d552ff11aae5cacc751eec409fc5",
                            sizeBytes = 3_471_080,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-nb-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 8,
                            uncompressedBytes = 38_192_067,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-no-2018",
                    displayName = "OpenSubtitles 2018 frequency (Norwegian)",
                    sourceId = "opensubtitles-no",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/no/no_50k.txt",
                            sha256 = "d5808d8ec04603b765c09c06a03f0273ccf36bb35a3902287e56b7e7d872ca92",
                            sizeBytes = 601_916,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-nb-en-2026.09.20", "opensubtitles-no-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-ro-en-2026.09.20",
                    displayName = "Wiktionary (Romanian-English) 2026-09-20",
                    slotId = "wty-ro-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/ro/en/wty-ro-en.zip",
                            sha256 = "07b48d37ab08fa48247cf9f072d93daf2d1f3d93fe37a8f94a5fda355dafd611",
                            sizeBytes = 12_392_803,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-ro-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 33,
                            uncompressedBytes = 177_246_548,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-ro-2018",
                    displayName = "OpenSubtitles 2018 frequency (Romanian)",
                    sourceId = "opensubtitles-ro",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/ro/ro_50k.txt",
                            sha256 = "af4d7d1fc980ee7f988a5114dd34478dd5c7abd1e66a45c25a5da330f06faba5",
                            sizeBytes = 654_282,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-ro-en-2026.09.20", "opensubtitles-ro-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-el-en-2026.09.20",
                    displayName = "Wiktionary (Greek-English) 2026-09-20",
                    slotId = "wty-el-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/el/en/wty-el-en.zip",
                            sha256 = "847d7127bb5c7229b604fc0d3cca073c4b0dd414a5e97bd4e94c02603f46f76f",
                            sizeBytes = 6_033_520,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-el-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 16,
                            uncompressedBytes = 75_810_782,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-el-2018",
                    displayName = "OpenSubtitles 2018 frequency (Greek)",
                    sourceId = "opensubtitles-el",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/el/el_50k.txt",
                            sha256 = "7ec8f6de52c38f36a04add5d1613b116aed576e10c5e3b40d0001e8572de978c",
                            sizeBytes = 1_018_575,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-el-en-2026.09.20", "opensubtitles-el-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-fi-en-2026.09.20",
                    displayName = "Wiktionary (Finnish-English) 2026-09-20",
                    slotId = "wty-fi-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/fi/en/wty-fi-en.zip",
                            sha256 = "7e16c3732c7ea62ea164dd5201ae80410b79a152ba5be7034e4f01766bdc2332",
                            sizeBytes = 43_308_354,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-fi-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 191,
                            uncompressedBytes = 568_675_087,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-fi-2018",
                    displayName = "OpenSubtitles 2018 frequency (Finnish)",
                    sourceId = "opensubtitles-fi",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/fi/fi_50k.txt",
                            sha256 = "8bc1d43dd54ea0c4432b1f6ae497759a332e984224819cf8a4f2337681c803b4",
                            sizeBytes = 691_168,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-fi-en-2026.09.20", "opensubtitles-fi-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-hu-en-2026.09.20",
                    displayName = "Wiktionary (Hungarian-English) 2026-09-20",
                    slotId = "wty-hu-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/hu/en/wty-hu-en.zip",
                            sha256 = "a6e15735b4f678e49172b44fc4ad8eca7849d7693b4a13dea32652a88ec65fda",
                            sizeBytes = 13_105_466,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-hu-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 59,
                            uncompressedBytes = 166_886_132,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-hu-2018",
                    displayName = "OpenSubtitles 2018 frequency (Hungarian)",
                    sourceId = "opensubtitles-hu",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/hu/hu_50k.txt",
                            sha256 = "b765780a1277c3b1e326205c972b1ea1631732ec54ab489d13d21618ceab4fec",
                            sizeBytes = 698_652,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-hu-en-2026.09.20", "opensubtitles-hu-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-sh-en-2026.09.20",
                    displayName = "Wiktionary (Serbo-Croatian-English) 2026-09-20",
                    slotId = "wty-sh-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/sh/en/wty-sh-en.zip",
                            sha256 = "d6293c86f86975e08f748c9e48178634a3789f675933301119be570d00cc3c0e",
                            sizeBytes = 11_959_737,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-sh-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 44,
                            uncompressedBytes = 171_035_458,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-hr-2018",
                    displayName = "OpenSubtitles 2018 frequency (Croatian)",
                    sourceId = "opensubtitles-hr",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/hr/hr_50k.txt",
                            sha256 = "725f57e0bd122f46d8e358c3542a4e1d126e468075c5e4d6644fd0d21c47bbc3",
                            sizeBytes = 630_560,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-sh-en-2026.09.20", "opensubtitles-hr-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-sv-en-2026.09.20",
                    displayName = "Wiktionary (Swedish-English) 2026-09-20",
                    slotId = "wty-sv-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/sv/en/wty-sv-en.zip",
                            sha256 = "34ccdd84b12f1a6a8785a1eaba858aa07ba578745fa4f29529a75786713060e6",
                            sizeBytes = 8_328_341,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-sv-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 20,
                            uncompressedBytes = 99_211_423,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-sv-2018",
                    displayName = "OpenSubtitles 2018 frequency (Swedish)",
                    sourceId = "opensubtitles-sv",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/sv/sv_50k.txt",
                            sha256 = "566d0614a54f3c243bca4097c9bb824d46275abff31582157e73c166b7089500",
                            sizeBytes = 623_913,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-sv-en-2026.09.20", "opensubtitles-sv-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-pl-en-2026.09.20",
                    displayName = "Wiktionary (Polish-English) 2026-09-20",
                    slotId = "wty-pl-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/pl/en/wty-pl-en.zip",
                            sha256 = "3cbedb1499475105649720a0612bf6022dae1c2a18fde0fb978ee5bc84133424",
                            sizeBytes = 21_991_170,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-pl-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 72,
                            uncompressedBytes = 281_577_147,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 67_108_864,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-pl-2018",
                    displayName = "OpenSubtitles 2018 frequency (Polish)",
                    sourceId = "opensubtitles-pl",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/pl/pl_50k.txt",
                            sha256 = "50bf8d30d1ed4f5cedceb3e40e74d793ec6bec0f16d9f3dd0a2c9e313402e10f",
                            sizeBytes = 676_499,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-pl-en-2026.09.20", "opensubtitles-pl-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-lt-en-2026.09.20",
                    displayName = "Wiktionary (Lithuanian-English) 2026-09-20",
                    slotId = "wty-lt-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/lt/en/wty-lt-en.zip",
                            sha256 = "01cec5943c4136c13278674fb418e0078e53531ba47b7884fc15a915ad0aa223",
                            sizeBytes = 2_259_176,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-lt-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 12,
                            uncompressedBytes = 28_704_832,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-lt-2018",
                    displayName = "OpenSubtitles 2018 frequency (Lithuanian)",
                    sourceId = "opensubtitles-lt",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/lt/lt_50k.txt",
                            sha256 = "e953d15442ceb0a2eefd2910ca1e01e1bbf5cb261dc8ac905c918c8ae3edfe31",
                            sizeBytes = 610_944,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-lt-en-2026.09.20", "opensubtitles-lt-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-da-en-2026.09.20",
                    displayName = "Wiktionary (Danish-English) 2026-09-20",
                    slotId = "wty-da-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/da/en/wty-da-en.zip",
                            sha256 = "4d072f93ea31894388b80b9822d2ba6d208607214bc5a7c4ad55076a11096760",
                            sizeBytes = 3_223_474,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-da-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 10,
                            uncompressedBytes = 39_400_119,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-da-2018",
                    displayName = "OpenSubtitles 2018 frequency (Danish)",
                    sourceId = "opensubtitles-da",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/da/da_50k.txt",
                            sha256 = "6a83e4deb5e38d873b1ccfb4be4fb25903878c07c8522af708027ac8b540c6b1",
                            sizeBytes = 616_396,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-da-en-2026.09.20", "opensubtitles-da-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-sl-en-2026.09.20",
                    displayName = "Wiktionary (Slovene-English, small) 2026-09-20",
                    slotId = "wty-sl-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/sl/en/wty-sl-en.zip",
                            sha256 = "e41357b12e82ef16b401ecc2d85b64045506292532a82cf1428df6806ed2d667",
                            sizeBytes = 828_222,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-sl-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 7,
                            uncompressedBytes = 9_887_582,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-sl-2018",
                    displayName = "OpenSubtitles 2018 frequency (Slovenian)",
                    sourceId = "opensubtitles-sl",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/sl/sl_50k.txt",
                            sha256 = "00d9dc7a50726e99ab4d5bb50ce8087ce709a1416afad65a9dec0821c03ba442",
                            sizeBytes = 619_289,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-sl-en-2026.09.20", "opensubtitles-sl-2018"),
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
                YomitanCatalogResource(
                    resourceId = "wty-ru-en-2026.09.20",
                    displayName = "Wiktionary (Russian-English) 2026-09-20",
                    slotId = "wty-ru-en",
                    archive =
                        ResourceArchive(
                            url = "https://huggingface.co/datasets/daxida/wty-release/resolve/9ff9d2855b7346905a0db9266123e1cd79e964a1/latest/dict/ru/en/wty-ru-en.zip",
                            sha256 = "3503b90bfd005a50e49580a3b52c4b07047e605b822b14d56c3e45687350a0cf",
                            sizeBytes = 26_133_529,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "wty-ru-en",
                            revision = "2026.09.20",
                            format = 3,
                            memberCount = 64,
                            uncompressedBytes = 289_865_117,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 67_108_864,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("Wiktionary", "Wiktionary contributors", "CC-BY-SA-4.0", "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"),
                            ResourceAttribution("wiktionary-to-yomitan", "wty contributors (Yomitan build of kaikki.org extracts)", "CC-BY-SA-4.0", "https://github.com/yomidevs/wiktionary-to-yomitan"),
                        ),
                ),
                YomitanCatalogResource(
                    resourceId = "opr-ru-en-2026.03.01",
                    displayName = "OpenRussian (Russian-English) 2026-03-01",
                    slotId = "opr-ru-en",
                    archive =
                        ResourceArchive(
                            url = "https://github.com/ImenaOphelia/openrussian-to-yomitan/releases/download/v2026.03.01/opr-ru-en.zip",
                            sha256 = "8f95d7179a04878c307be65d10e58202e6decc8553eff799635597d44cb0fbd5",
                            sizeBytes = 28_179_164,
                            format = "zip",
                        ),
                    dictionary =
                        YomitanDictionaryIdentity(
                            title = "opr-ru-en",
                            revision = "2026.03.01",
                            format = 3,
                            memberCount = 70,
                            uncompressedBytes = 559_774_019,
                            archiveMemberLimit = 4096,
                            uncompressedBytesLimit = 2_147_483_648,
                            fileBytesLimit = 33_554_432,
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("OpenRussian.org", "OpenRussian.org contributors", "CC-BY-SA-4.0", "https://en.openrussian.org/"),
                            ResourceAttribution("openrussian-to-yomitan", "ImenaOphelia (Yomitan build of OpenRussian.org data)", "CC-BY-SA-4.0", "https://github.com/ImenaOphelia/openrussian-to-yomitan"),
                        ),
                ),
                FrequencyCatalogResource(
                    resourceId = "opensubtitles-ru-2018",
                    displayName = "OpenSubtitles 2018 frequency (Russian)",
                    sourceId = "opensubtitles-ru",
                    archive =
                        ResourceArchive(
                            url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/ru/ru_50k.txt",
                            sha256 = "6095f507cc167488ec66ada5a85ac50433503a08ad24a07c6eabdf54352c4e7f",
                            sizeBytes = 998_861,
                            format = "txt",
                        ),
                    attribution =
                        listOf(
                            ResourceAttribution("FrequencyWords", "Copyright (c) 2016 Hermit Dave", "CC-BY-SA-4.0", "https://github.com/hermitdave/FrequencyWords"),
                            ResourceAttribution("OpenSubtitles 2018 corpus", "OPUS (opus.nlpl.eu) and OpenSubtitles.org", "CC-BY-SA-4.0", "https://opus.nlpl.eu/OpenSubtitles2018.php"),
                        ),
                ),
            ),
        recommended = listOf("wty-ru-en-2026.09.20", "opr-ru-en-2026.03.01", "opensubtitles-ru-2018"),
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
