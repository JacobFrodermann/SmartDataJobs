window['select_target_options'] = {
    showWhenNoData: true,
    onChangeSelections: function (evt) {
        // You are inside the component here
        let elem = document.querySelector('#select_target');
        let inputs = elem.swac_comp.getInputs();
        let source;
        for (let curInput of inputs) {
            source = curInput.name;
        }
        window['adm_presets_options'].targetSource = {
            url: '/' + source + '/smartdata/[iface]/[fromName]?storage=smartmonitoring',
            interfaces: {
                get: ['GET', 'records'],
                list: ['GET', 'records'],
                defs: ['GET', 'collection'],
                cdefs: ['POST', 'collection'],
                create: ['POST', 'records'],
                update: ['PUT', 'records'],
                delete: ['DELETE', 'records']
            }
        };

        // if presets is already loaded
        let presetsElem = document.querySelector('#adm_presets');
        if (typeof presetsElem.swac_comp !== "undefined") {
            presetsElem.swac_comp.reload();
        }
    }
};
window['adm_presets_options'] = {
    showWhenNoData: true
};

// if presents component is not ready
document.addEventListener('swac_ready', function () {
    // Register reaction to the component with id "head_navigation"
    window.swac.reactions.addReaction(function () {
        let presetsElem = document.querySelector('#adm_presets');
        presetsElem.swac_comp.reload();
    }, "adm_presets", "select_target");
});
