// Backend connection settings
var SWAC_config = {
    lang: 'de',
    notifyDuration: 5000,
    remoteTimeout: 50000,
    debugmode: true,
    debug: 'all',
    globalparams: {
        api_name: 'SmartDataJobs',
        api_name_lc: 'smartdatajobs',
        api_desc: 'This REST API allows controlling and execution recurring jobs.',
        api_link: 'http://git01-ifm-min.ad.fh-bielefeld.de/Forschung/smartecosystem/smartdata/-/wikis/home'
    },
    datasources: [
        {
            url: "[fromName]"
        },
        {
            url: "../[fromName]"
        }
    ],
    progressive: {
        active: false
    },
    onlinereactions: []
};

/**
 * Links for footer navigation
 */
var footerlinks = [
    {id: 1, rfrom: "*", rto: "datenschutz.html", name: "Datenschutzerklärung"},
    {id: 2, rfrom: "*", rto: "impressum.html", name: "Impressum"},
    {id: 3, rfrom: "*", rto: "http://git01-ifm-min.ad.fh-bielefeld.de/Forschung/smartecosystem/smartdata/-/wikis/home", name: "Über SmartData"}
];
